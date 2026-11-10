#!/usr/bin/env python3
"""W34.23 CI-only separate-process witness / idempotent receiver lab.

NOT real independent administrative custody, WORM, finance signature, or production delivery.
Python standard library + system openssl; loopback-only; disposable temporary stores.
"""
import base64
import hashlib
import http.client
import json
import os
import pathlib
import re
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PREFIX = 'W3423|v1'
CASES = ['W3423-%02d' % i for i in range(1, 13)]

def digest(s):
    return hashlib.sha256(s.encode()).hexdigest()

def canonical(x):
    return '|'.join([PREFIX, x['claim_id'], x['digest'], x['checkpoint'], x['key_id']]).encode()

def sqlite(path):
    c = sqlite3.connect(str(path), timeout=10, isolation_level=None)
    c.execute('PRAGMA journal_mode=WAL')
    c.execute('PRAGMA busy_timeout=5000')
    return c

class Service(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        return

    def reply(self, status, obj):
        data = json.dumps(obj, sort_keys=True).encode()
        self.send_response(status)
        self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        role, store, auth = self.server.role, self.server.store, self.server.auth
        path = urllib.parse.urlsplit(self.path)
        q = urllib.parse.parse_qs(path.query)
        if role == 'witness' and path.path == '/trust':
            with sqlite(store/'witness.db') as c:
                keys = [dict(key_id=r[0], revoked=bool(r[1])) for r in c.execute('SELECT key_id,revoked FROM keys')]
            return self.reply(200, {'keys': keys, 'public_key_pem': (store/'pub.pem').read_text()})
        if role == 'witness' and path.path == '/receipt':
            with sqlite(store/'witness.db') as c:
                row = c.execute('SELECT payload,signature FROM receipts WHERE claim_id=?', (q.get('claim_id',[''])[0],)).fetchone()
            return self.reply(200 if row else 404, {'receipt': json.loads(row[0]), 'signature':row[1]} if row else {'error':'missing'})
        if role == 'receiver' and path.path == '/status':
            with sqlite(store/'receiver.db') as c:
                row = c.execute('SELECT digest,effect_minor,receipt FROM inbox WHERE message_id=?', (q.get('message_id',[''])[0],)).fetchone()
                effects = c.execute('SELECT COUNT(*),COALESCE(SUM(effect_minor),0) FROM effects').fetchone()
            return self.reply(200, {'row': list(row) if row else None, 'effect_rows':effects[0], 'effect_total_minor':effects[1]})
        return self.reply(404, {'error':'unknown route'})

    def do_POST(self):
        role, store, auth = self.server.role, self.server.store, self.server.auth
        if int(self.headers.get('Content-Length','0')) > 16384:
            return self.reply(413, {'error':'oversized'})
        try:
            data = json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
            assert isinstance(data, dict)
        except Exception:
            return self.reply(400, {'error':'invalid json'})
        if role == 'witness' and self.path == '/revoke':
            if self.headers.get('X-Test-Admin') != auth:
                return self.reply(403, {'error':'admin-only'})
            with sqlite(store/'witness.db') as c:
                c.execute('UPDATE keys SET revoked=1 WHERE key_id=?', (data.get('key_id'),))
            return self.reply(200, {'revoked':True})
        if role == 'witness' and self.path == '/attest':
            claim_id = data.get('claim_id',''); d = data.get('digest',''); checkpoint = data.get('checkpoint','')
            if not (isinstance(claim_id,str) and re.fullmatch(r'[A-Za-z0-9_-]{1,90}',claim_id)
                    and isinstance(d,str) and re.fullmatch(r'[a-f0-9]{64}',d)
                    and isinstance(checkpoint,str) and re.fullmatch(r'[A-Za-z0-9_-]{1,90}',checkpoint)):
                return self.reply(400, {'error':'invalid claim'})
            with sqlite(store/'witness.db') as c:
                c.execute('BEGIN IMMEDIATE')
                old = c.execute('SELECT payload,signature FROM receipts WHERE claim_id=?',(claim_id,)).fetchone()
                key = c.execute('SELECT key_id,revoked FROM keys').fetchone()
                receipt = {'claim_id':claim_id,'digest':d,'checkpoint':checkpoint,'key_id':key[0]}
                if key[1]:
                    c.rollback(); return self.reply(423, {'error':'signer revoked'})
                if old:
                    same = json.loads(old[0]) == receipt
                    c.commit()
                    return self.reply(200 if same else 409, {'receipt':receipt,'signature':old[1]} if same else {'error':'changed claim'})
                p = subprocess.run(['openssl','dgst','-sha256','-sign',str(store/'key.pem')],input=canonical(receipt),capture_output=True)
                if p.returncode:
                    c.rollback(); return self.reply(503,{'error':'signing unavailable'})
                signature=base64.b64encode(p.stdout).decode()
                c.execute('INSERT INTO receipts VALUES(?,?,?)',(claim_id,json.dumps(receipt,sort_keys=True),signature))
                c.commit()
            return self.reply(201,{'receipt':receipt,'signature':signature})
        if role == 'receiver' and self.path == '/deliver':
            mid=data.get('message_id',''); d=data.get('digest',''); effect=data.get('effect_minor')
            if not (isinstance(mid,str) and re.fullmatch(r'[A-Za-z0-9_-]{1,90}',mid)
                    and isinstance(d,str) and re.fullmatch(r'[a-f0-9]{64}',d)
                    and type(effect) is int and 0 < effect < 1000000):
                return self.reply(400, {'error':'invalid message'})
            if self.headers.get('X-Fail-Before-Commit') == '1':
                return self.reply(503, {'error':'injected precommit outage'})
            receipt = digest('RECEIVER|'+mid+'|'+d+'|'+str(effect))
            with sqlite(store/'receiver.db') as c:
                c.execute('BEGIN IMMEDIATE')
                old=c.execute('SELECT digest,effect_minor,receipt FROM inbox WHERE message_id=?',(mid,)).fetchone()
                if old:
                    c.commit()
                    if old != (d,effect,receipt):
                        return self.reply(409, {'error':'changed event conflict'})
                    result=200
                else:
                    c.execute('INSERT INTO inbox VALUES(?,?,?,?)',(mid,d,effect,receipt))
                    c.execute('INSERT INTO effects VALUES(?,?)',(mid,effect))
                    c.commit()
                    result=201
            if self.headers.get('X-Drop-After-Commit') == '1':
                try: self.connection.shutdown(socket.SHUT_RDWR)
                except OSError: pass
                self.connection.close()
                return
            return self.reply(result, {'receipt':receipt,'message_id':mid})
        return self.reply(404, {'error':'unknown route'})

def serve(role,store,port,auth):
    store=pathlib.Path(store)
    store.mkdir(parents=True,exist_ok=True)
    if role=='witness':
        if not (store/'key.pem').exists():
            subprocess.run(['openssl','ecparam','-name','prime256v1','-genkey','-noout','-out',str(store/'key.pem')],check=True,stdout=subprocess.DEVNULL)
            os.chmod(store/'key.pem',0o600)
            subprocess.run(['openssl','pkey','-in',str(store/'key.pem'),'-pubout','-out',str(store/'pub.pem')],check=True,stdout=subprocess.DEVNULL)
        with sqlite(store/'witness.db') as c:
            c.execute('CREATE TABLE IF NOT EXISTS keys (key_id TEXT PRIMARY KEY,revoked INTEGER NOT NULL)')
            c.execute('CREATE TABLE IF NOT EXISTS receipts (claim_id TEXT PRIMARY KEY,payload TEXT NOT NULL,signature TEXT NOT NULL)')
            c.execute('INSERT OR IGNORE INTO keys VALUES(?,0)', ('W3423_TEST_P256_1',))
    else:
        with sqlite(store/'receiver.db') as c:
            c.execute('CREATE TABLE IF NOT EXISTS inbox (message_id TEXT PRIMARY KEY,digest TEXT NOT NULL,effect_minor INTEGER NOT NULL,receipt TEXT NOT NULL)')
            c.execute('CREATE TABLE IF NOT EXISTS effects (message_id TEXT PRIMARY KEY,effect_minor INTEGER NOT NULL)')
    server=ThreadingHTTPServer(('127.0.0.1',port),Service)
    server.role=role;server.store=store;server.auth=auth
    server.serve_forever(poll_interval=0.1)

def request(port, route, obj=None, headers=None, timeout=3):
    url='http://127.0.0.1:%s%s'%(port,route)
    raw=json.dumps(obj).encode() if obj is not None else None
    h={'Content-Type':'application/json'} if raw else {}
    h.update(headers or {})
    try:
        with urllib.request.urlopen(urllib.request.Request(url,data=raw,headers=h,method='POST' if raw else 'GET'),timeout=timeout) as r:
            return r.status,json.load(r)
    except urllib.error.HTTPError as e:
        return e.code,json.load(e)

def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));return sock.getsockname()[1]

class Runner:
    def __init__(self,root):
        self.root=pathlib.Path(root);self.auth=digest('w3423-admin-'+str(time.monotonic_ns()))
        self.processes={};self.ports={}
    def start(self,role):
        p=port();self.ports[role]=p
        args=[sys.executable,__file__,'serve',role,str(self.root/role),str(p),self.auth]
        self.processes[role]=subprocess.Popen(args,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE)
        until=time.time()+5
        while time.time()<until:
            if self.processes[role].poll() is not None:
                raise RuntimeError('service exited: '+self.processes[role].stderr.read().decode()[-1000:])
            try:
                request(p,'/trust' if role=='witness' else '/status?message_id=health',timeout=0.2)
                return
            except (OSError,urllib.error.URLError):time.sleep(0.03)
        raise RuntimeError(role+' not ready')
    def stop(self,role):
        proc=self.processes.pop(role,None)
        if proc:
            proc.terminate()
            try:proc.wait(timeout=3)
            except subprocess.TimeoutExpired:proc.kill();proc.wait()
    def close(self):
        for role in list(self.processes):self.stop(role)

class Outbox:
    def __init__(self,path):
        self.path=path
        with sqlite(path) as c:
            c.execute('CREATE TABLE outbox (message_id TEXT PRIMARY KEY, digest TEXT NOT NULL, effect_minor INTEGER NOT NULL, state TEXT NOT NULL, owner TEXT, generation INTEGER NOT NULL DEFAULT 0, lease_until INTEGER NOT NULL DEFAULT 0, receipt TEXT)')
    def add(self,mid,d,effect):
        with sqlite(self.path) as c:c.execute("INSERT INTO outbox(message_id,digest,effect_minor,state) VALUES(?,?,?,'PENDING')",(mid,d,effect))
    def claim(self,mid,owner,now):
        with sqlite(self.path) as c:
            c.execute('BEGIN IMMEDIATE')
            row=c.execute('SELECT state,lease_until,generation FROM outbox WHERE message_id=?',(mid,)).fetchone()
            if not row or row[0]=='ACKED' or (row[0]=='CLAIMED' and row[1]>now):
                c.commit();return None
            gen=row[2]+1
            c.execute("UPDATE outbox SET state='CLAIMED',owner=?,generation=?,lease_until=? WHERE message_id=?",(owner,gen,now+10,mid))
            c.commit();return gen
    def ack(self,mid,owner,gen,receipt):
        with sqlite(self.path) as c:
            c.execute('BEGIN IMMEDIATE')
            row=c.execute('SELECT digest,effect_minor,state,owner,generation FROM outbox WHERE message_id=?',(mid,)).fetchone()
            if not row or row[2]!='CLAIMED' or row[3]!=owner or row[4]!=gen:
                c.rollback();return False
            expected=digest('RECEIVER|'+mid+'|'+row[0]+'|'+str(row[1]))
            if receipt!=expected:
                c.rollback();return False
            c.execute("UPDATE outbox SET state='ACKED',receipt=? WHERE message_id=?",(receipt,mid))
            c.commit();return True
    def state(self,mid):
        with sqlite(self.path) as c:return c.execute('SELECT state,generation,receipt FROM outbox WHERE message_id=?',(mid,)).fetchone()

def verify(receipt,pub):
    with tempfile.TemporaryDirectory() as t:
        t=pathlib.Path(t);(t/'pub').write_text(pub)
        (t/'msg').write_bytes(canonical(receipt['receipt']))
        (t/'sig').write_bytes(base64.b64decode(receipt['signature'],validate=True))
        r=subprocess.run(['openssl','dgst','-sha256','-verify',str(t/'pub'),'-signature',str(t/'sig'),str(t/'msg')],capture_output=True)
        return r.returncode==0

def test():
    assert os.environ.get('W34_DISPOSABLE_ONLY')=='true' and os.environ.get('CI')=='true', 'DISPOSABLE CI ONLY'
    head=os.environ.get('EXPECTED_HEAD','')
    assert re.fullmatch(r'[0-9a-f]{40}',head), 'exact source SHA required'
    results=[]
    def check(idx,fn):
        fn();results.append({'case_id':CASES[idx-1],'status':'PASS','source_sha':head,'independent_administration':False,'external_provider_verified':False,'production_authorized':False})
    with tempfile.TemporaryDirectory(prefix='wave3423_disposable_') as td:
        r=Runner(td)
        try:
            r.start('witness');r.start('receiver')
            wp=r.ports['witness'];rp=r.ports['receiver']
            witness={'claim_id':'CLAIM-01','digest':digest('claim-original'),'checkpoint':'CP-01'}
            status,first=request(wp,'/attest',witness)
            assert status==201
            pub=request(wp,'/trust')[1]['public_key_pem']
            check(1,lambda: (verify(first,pub) or (_ for _ in ()).throw(AssertionError('bad witness signature'))))
            def tamper():
                mutated=json.loads(json.dumps(first));mutated['receipt']['digest']=digest('tampered-primary')
                assert not verify(mutated,pub)
                assert request(wp,'/receipt?claim_id=CLAIM-01')[1]['receipt']==first['receipt']
            check(2,tamper)
            def forged():
                altered=json.loads(json.dumps(first));altered['signature']=base64.b64encode(b'x'*72).decode()
                assert not verify(altered,pub)
            check(3,forged)
            def witness_conflict():
                assert request(wp,'/attest',witness)[0]==200
                assert request(wp,'/attest',dict(witness,digest=digest('changed')))[0]==409
            check(4,witness_conflict)
            def precommit():
                data={'message_id':'MSG-PRE','digest':digest('before-commit'),'effect_minor':13}
                assert request(rp,'/deliver',data,{'X-Fail-Before-Commit':'1'})[0]==503
                assert request(rp,'/status?message_id=MSG-PRE')[1]['row'] is None
                assert request(rp,'/deliver',data)[0]==201
            check(5,precommit)
            original={'message_id':'MSG-ORIG','digest':digest('received-01'),'effect_minor':21}
            status,receipt=request(rp,'/deliver',original)
            check(6,lambda: (status==201 and bool(receipt['receipt'])) or (_ for _ in ()).throw(AssertionError('missing receipt')))
            def duplicate():
                status,again=request(rp,'/deliver',original)
                assert status==200 and again==receipt
            check(7,duplicate)
            check(8,lambda: (request(rp,'/deliver',dict(original,digest=digest('changed')))[0]==409) or (_ for _ in ()).throw(AssertionError('changed duplicate accepted')))
            out=Outbox(pathlib.Path(td)/'primary_outbox.db')
            crashed={'message_id':'MSG-CRASH','digest':digest('after-send-before-ack'),'effect_minor':34}
            out.add(crashed['message_id'],crashed['digest'],crashed['effect_minor'])
            def crash():
                assert out.claim('MSG-CRASH','worker-A',100)==1
                try:request(rp,'/deliver',crashed,{'X-Drop-After-Commit':'1'})
                except (http.client.RemoteDisconnected,urllib.error.URLError,OSError):pass
                assert request(rp,'/status?message_id=MSG-CRASH')[1]['row'] is not None
                assert out.state('MSG-CRASH')[0]=='CLAIMED'
                assert out.claim('MSG-CRASH','worker-B',111)==2
                status,ack=request(rp,'/deliver',crashed)
                assert status==200
                assert out.ack('MSG-CRASH','worker-B',2,ack['receipt'])
                assert out.state('MSG-CRASH')[0]=='ACKED'
            check(9,crash)
            def fence():
                assert not out.ack('MSG-CRASH','worker-A',1,receipt['receipt'])
                assert not out.ack('MSG-CRASH','worker-B',2,receipt['receipt'])
                assert out.state('MSG-CRASH')[0]=='ACKED'
            check(10,fence)
            def restart():
                before=request(rp,'/status?message_id=MSG-CRASH')[1]
                r.stop('receiver');r.start('receiver');new=r.ports['receiver']
                after=request(new,'/status?message_id=MSG-CRASH')[1]
                assert before==after
                status,replayed=request(new,'/deliver',crashed)
                assert status==200 and replayed['receipt']==after['row'][2]
            check(11,restart)
            def revoke():
                assert request(wp,'/revoke',{'key_id':'W3423_TEST_P256_1'})[0]==403
                assert request(wp,'/revoke',{'key_id':'W3423_TEST_P256_1'},{'X-Test-Admin':r.auth})[0]==200
                trust=request(wp,'/trust')[1]
                assert trust['keys'][0]['revoked'] is True
                assert verify(first,trust['public_key_pem'])  # cryptographic validity survives revocation
                assert request(wp,'/attest',{'claim_id':'CLAIM-NEW','digest':digest('revoked'),'checkpoint':'CP-02'})[0]==423
            check(12,revoke)
        finally:r.close()
    assert [x['case_id'] for x in results]==CASES
    output={'source_sha':head,'cases':results,'passed':len(results),'failed':0,'service_processes':'separate localhost worker processes','store_boundary':'three separate SQLite files','witness_algorithm':'OpenSSL ECDSA P-256 SHA256','real_independent_administration':False,'real_financial_provider_or_effects':False,'production_authorized':False,'release_disposition':'NO-GO'}
    pathlib.Path('wave34-23-evidence').mkdir(exist_ok=True)
    pathlib.Path('wave34-23-evidence/w3423-results.json').write_text(json.dumps(output,sort_keys=True,indent=2)+'\n')
    print(json.dumps({'passed':len(results),'source_sha':head,'status':'PASS','independence_claim':'PROCESS_ISOLATION_ONLY','release_disposition':'NO-GO'}))

if __name__=='__main__':
    if len(sys.argv)>1 and sys.argv[1]=='serve':
        assert os.environ.get('W34_DISPOSABLE_ONLY')=='true' and os.environ.get('CI')=='true'
        serve(sys.argv[2],sys.argv[3],int(sys.argv[4]),sys.argv[5])
    elif len(sys.argv)>1 and sys.argv[1]=='test':test()
    else:raise SystemExit('usage: script serve role store port admin_token | test')
