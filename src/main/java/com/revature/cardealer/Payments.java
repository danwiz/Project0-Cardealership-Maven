package com.revature.cardealer;

public class Payments {
	
	private int aOwed;
	private int aPaid;
	private int aDue;
	private int pBalance;
	private int pLength;
	private int tPaid;
	private int iPayments[]= new int[20];
	private String payments[]= new String[20];
	private String pPayment;
	private int pIndex;
	private String cName[] = new String[20];
	
	
	
	//Mutators
	public void setAmtOwed (int owed) {		
		aOwed=owed;	
		
	}
	
	public void setAmtPaid (int paid) {		
		aPaid =paid;	
		
	}
	
	public void setAmtDue(int due) {		
		aPaid =due;		
	}
	
	public void balance (int bal) {		
		pBalance =bal;			
	}
	
	
	public void Pmtlength (int len) {		
		pLength = len;			
	}

	
	//Accessors
	public int getAmtOwed() {
		return aOwed;
	}
	
	public int getAmtPaid() {
		return aPaid;
	}
	
	public int getAmtDue() {
		return aDue;
	}
	
	public int getBalance() {
		return pBalance;
	}
	
	public int getPmtLength() {
		return pLength;
	}
	
	//Util
	
	//"   Cost:-  "+aOwned+   Amounts Paid: "+tPaid+ "   Balance:  "+pBalance+"   Monthly Due:   "+aDue+ ";
   
	public void getPaymentsAll() {
		
		for(int i=0; i<=payments.length-1; i++) {
			if(payments[i]!=null) {
				System.out.println("[" + i + "]" + payments[i] +"   Customer:  "+ cName[i]);
		}
		System.out.println("\nWe have Payments at this Time!");
			
		}
		
	}
	
	public void makePayment(String cname, int pamt) {
		aPaid=pamt;
		tPaid= tPaid+ aPaid;
		pBalance=aOwed-tPaid;
		iPayments[pIndex]=aPaid;
		cName[pIndex]=cname;
		payments[pIndex]="Cost:-  "+aOwed+ " Amounts Paid: "+tPaid+ "   Balance:  "+pBalance+ "   Monthly Due:   "+aDue;
		//payment"Cost:-  "+aOwed+ " Amounts Paid: "+tPaid+ "   Balance:  "+pBalance+ "   Monthly Due:   "+aDue);
		pIndex++;
		
		
	}

	public int MPay(int len) {
		if(len!=0)
			return (aDue=aOwed/len);
			pPayment= pPayment+"\nCost:-  "+aOwed+ " Amounts Paid: "+tPaid+ "   Balance:  "+pBalance+ "   Monthly Due:   "+aDue;
		return 0;
	}

	public String getpPayment() {
		return pPayment;
	}

	public void setpPayment(String cname, int cprice, int len) {
		//pdIndex
		//pdPayment = pPayment;
	}
}
