package com.revature.cardealer;

public class Offer {

	private String oAvail;
	private int oAmt;
	private int oMpay;
	private int oPrice;
	private boolean oAccept;
	private boolean oStatus;
	private boolean oPending;
	//private String status;
	private String oDetails;
	private Offer[] oOfferDB= new Offer[20];
	// private String offer; // Car - Make: model: Year: "+ Price: + Avail
	String[] offerDB = new String[20];
	private int oIndex = 0;
	private Car cDetails;
	private Car[] carDB = new Car[20];
	private String cName[] = new String[20];
	private int cpOffer[] = new int[20];
	private int cpPrice[] = new int[20];
	private int opIndex = 0;
	private String oCName;

	// private Offer[] oOfferDB = new Offer[20];
	// private int cIndex;

	/*
	 * public Offer() {
	 * 
	 * Offer[] offerDB = {registerOffer("Honda", "Accord", 2018, 15456,"Yes",7),
	 * registerOffer("Chevy", "Malibu", 2020, 17456,"Yes",4), registerOffer("Honda",
	 * "Accord", 2016, 10456,"Yes",6), registerOffer("Honda", "Accord", 2014,
	 * 13456,"Yes",3)};
	 * 
	 * for(int i=0; i>= offerDB.length;i++) {
	 * System.out.println(offerDB[i].toString());
	 * 
	 * } }
	 * 
	 * 
	 * 
	 * public void setOfferDB(Offer offer) {
	 * 
	 * 
	 * Offer[] offerDB = {registerOffer("Honda", "Accord", 2018, 15456,"Yes",7),
	 * registerOffer("Chevy", "Malibu", 2020, 17456,"Yes",4), registerOffer("Honda",
	 * "Accord", 2016, 10456,"Yes",6), registerOffer("Honda", "Accord", 2014,
	 * 13456,"Yes",3)};
	 * 
	 * 
	 * for(int i=0; i<= offerDB.length;i++) {
	 * System.out.println(offerDB[i].toString());
	 * 
	 * 
	 * 
	 * oOfferDB[oIndex]= offer; oIndex++;
	 * 
	 * 
	 * 
	 * }
	 */

	public void setpOffer(String cname, int onum) {

		cpOffer[opIndex] = onum;
		cName[opIndex] = cname;
		//status= "   Pending Offer: " + cName[onum];
		//oDetails = "["+onum+"]"+ offerDB[onum] + " Pending Offer: " + cName[onum];
		//2cpOffer[onum] =""+oDetails+ "   Pending Offer: " + cName[onum];
		
		System.out.println("["+onum+"]"+ offerDB[onum] + "     Pending Offer for : " + cName[onum]);
		oPending=true;
		oOfferDB[oIndex]=this;
		oIndex++;
		opIndex++;

	}

	
	
	public void getpOffers() {
		
		for (int i = 0; i <= cpOffer.length; i++) {
			if(cpOffer[i]!=0 && cName[i] !=null)
			{
				int j = cpOffer[i];
				System.out.println(offerDB[j] + "   |--->  Pending Offer:   " + cName[j]);
			}
		System.out.println("\nWe have no Cars to at this Time!");
		}

	}

	public void registerOffer(String make, String model, int year, int price, String avail, int amt) {

		cDetails = new Car(make, model, year);
		setOffer(cDetails, price, avail, amt);
		carDB[oIndex] = cDetails;
		cpPrice[oIndex]=price;
		//offerDB[oIndex]=oDetails;
		offerDB[oIndex] = getOffer();
		
		/*
		 * Offer offer = new Offer(); offer.cDetails=cDetails; offer.oPrice = price;
		 * offer.oAvail=avail; offer.oAmt=amt; offer.oPending=false;
		 * offer.oAccept=false; offer.oStatus=true; offer.oIndex=oIndex;
		 */
		  
		//Used "this" store offer instend 
		  
		oOfferDB[oIndex]=this;   //stores this offer in offerDB
		oIndex++;

	}

	public void setOffer(Car details, int price, String avail, int amt) {

		cDetails = details;
		oPrice = price;
		oAvail = "yes";
		oAmt = amt;

		//oDetails = getOffer();
		// offer= cDetails.toString()+price+avail+;

	}

	public void setOffer(String make, String model, int year, int price, String avail, int amt) {
		cDetails = new Car(make, model, year);
		oPrice = price;
		oAvail = "yes";
		oAmt = amt;
		oStatus = true;
		oAccept = false;
		oPending=false;
		

		// offer= cDetails.getCarMake()+" "+cDetails.getCarModel()+"
		// "+cDetails.getCarYear() + " "+ price+" "+avail+" "+amt;
	}

	public void getOfferAll() {

		for (int i = 0; i <= offerDB.length-1; i++) {
			if(offerDB[i]!=null)
				System.out.println("[" + i + "]" + offerDB[i]);
		}
		System.out.println("\nWe have no Cars to at this Time!");
	}

	public String getOffer() {

		String offer = "[" + oIndex + "]   " + " Car:-  " + cDetails.getCarMake() + "   Model: "
				+ cDetails.getCarModel() + "   Year: " + cDetails.getCarYear() + "   Price: " + oPrice + "   Avail: "
				+ oAvail + "   Stock Qty: " + oAmt;
		System.out.println(offer);
		return offer;
	}

	public String toString() {
		return getOffer();
	}

	public void setPrice(int price) {
		oPrice = price;
	}

	public int getPrice() {
		return oPrice;
	}

	public void setMpay(int mpay) {
		
		oMpay = mpay;
	}

	public int getMpay() {
		return oMpay;
	}

	public String getAvail() {
		return oAvail;
	}

	public void setAvail(String avail) {
		oAvail = avail;
	}

	public void setAmt(int amt) {
		oAmt = amt;
	}

	public int getAmt() {
		return oAmt;
	}

	public boolean isAvail() {
		if (oAmt == 0)
			oStatus = false;
		return oStatus;
	}

	public void setStatus(boolean status) {
		oStatus = status;

	}

	public boolean getStatus() {
		return oStatus;
	}

	public void getAccept(Offer[] offerdb) {
		for (int i = 0; i >= offerdb.length; i++) {
			if (offerdb[i].oAccept)
				System.out.println(offerdb[i].oDetails + "   Offer Accepted: " + offerdb[i].oCName);
			oAccept = false;
		}

	}

	public void setAccept() {
		oAccept=true;	
	}
	// serches
	public int setAccept(int onum, int mth, boolean accept) {
		if (accept) {
			
			
			for (int i = 0; i >= cpOffer.length; i++) {
				if (cpOffer[i]==onum ) {//onum is offer number
					oAmt = oAmt - 1;
					oAccept = true;
					//System.out.println("Please Enter Number of Monthly Payments: ");					
					System.out.println(offerDB[onum] + "   Offer Accepted: " + cName[onum]);
					oMpay = cpPrice[onum]/mth;
					oDetails= offerDB[onum] + "   Offer Accepted: " + cName[onum]+"   Monthly Payment:  "+oMpay; //remember to update amt
					oCName = cName[onum];
					oOfferDB[oIndex]=this;   //stores this offer in offerDB
					oIndex++;
					return oMpay;
					// Offer accept = offerDB[i];
				} else
					oAccept = false;
			}

		}
		return cpPrice[onum];

	}

	public void rejectAllOffers() {
		
		opIndex = 0;// could just do this 
		for(int i=0; i<=cpOffer.length-1; i++)
		{
			cpOffer[0] = 0;
			cpPrice[0] = 0;
			cName[0]=" ";
			
			//offer ObjectDB Object.pending=false;
		}
	
		System.out.println("\nMessage: All Pending Offers REJECTED!! ");
	
	}
	
	public void removeOffer(int onum) {
		
		System.out.println(offerDB[onum]+"\nMessage: Car Listing Removed!! ");
		if(offerDB[onum]!=null)
			offerDB[onum] = "";
		//consider removing car for carDB?
	    //Offer ObjectDB  [onum].status=false
			
	}
	
	public boolean getAccept() {
		return oAccept;
	}

	public Car getCarDB(int pnum) {
		return carDB[pnum];
	}

}
