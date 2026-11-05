package com.revature.service;

import java.security.MessageDigest;
import java.util.Objects;

import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuthenticatedSignedPinPublisher.DurableDocumentReadback;
import com.revature.service.SignedReceiptPinIndex.SignedPinDocument;

/**
 * Provider-neutral, append-only publication and version-qualified read-back.
 *
 * The atomic object store must enforce first-writer-wins and durable exact
 * revision reads independently. In particular, it MUST NOT overwrite a
 * collided revision or report a non-durable write as acknowledged.
 */
public final class ImmutableSignedPinDocumentPublisher implements DurableDocumentReadback {
    private static final String BLOCKED = "immutable pin publication unavailable";

    private final SignedReceiptPinIndex verifier;
    private final ImmutableDocumentStore store;

    public ImmutableSignedPinDocumentPublisher(SignedReceiptPinIndex verifier,
            ImmutableDocumentStore store) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * First-writer-wins append; after success, conflict or unknown commit,
     * independently re-read the exact revision. Only identical signed bytes
     * permit retry/continuation; any collision or absence is a hard denial.
     */
    public void ensurePublished(ReopenProof proof, SignedPinDocument document) {
        if (document == null) throw blocked();
        String key = document.canonicalKey();
        long revision = document.revision();
        try {
            verifier.verifyDocument(proof, key, document);
            try {
                store.putIfAbsent(key, revision, document);
            } catch (Exception unknownCommit) {
                // The write may have committed before its acknowledgement was
                // lost. Never retry with overwrite or assume it was absent.
            }
            SignedPinDocument persisted = read(proof, key, revision);
            if (!same(document, persisted)) throw blocked();
        } catch (Exception unavailable) {
            throw blocked();
        }
    }

    @Override
    public SignedPinDocument read(ReopenProof proof, String canonicalKey,
            long expectedRevision) {
        if (canonicalKey == null || expectedRevision <= 0) throw blocked();
        try {
            SignedPinDocument persisted = store.readExact(canonicalKey, expectedRevision);
            if (persisted == null || persisted.revision() != expectedRevision) throw blocked();
            verifier.verifyDocument(proof, canonicalKey, persisted);
            return persisted;
        } catch (Exception unavailable) {
            throw blocked();
        }
    }

    private static boolean same(SignedPinDocument first, SignedPinDocument second) {
        return MessageDigest.isEqual(SignedReceiptPinIndex.signingPayload(first),
                SignedReceiptPinIndex.signingPayload(second))
                && MessageDigest.isEqual(first.signature(), second.signature());
    }

    private static SecurityException blocked() { return new SecurityException(BLOCKED); }

    public interface ImmutableDocumentStore {
        /**
         * Atomic durable put-if-absent for (canonicalKey, revision).
         * Return true when newly created, false if the revision already exists.
         * Never replace an existing value; on unknown commit, throw.
         */
        boolean putIfAbsent(String canonicalKey, long revision,
                SignedPinDocument document) throws Exception;

        /** Authenticated, bounded read of this exact immutable revision. */
        SignedPinDocument readExact(String canonicalKey, long revision) throws Exception;
    }
}
