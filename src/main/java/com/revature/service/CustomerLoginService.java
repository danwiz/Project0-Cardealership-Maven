package com.revature.service;

import com.revature.cardealer.Car;
import com.revature.cardealer.User;

//import com.revature.cardealer.Customer;
//import com.revature.cardealer.User;

public class CustomerLoginService extends UserLoginService {

	private Car[] cOwnedDB = new Car[20];
	private int cOwnedIndex = 0;
	private int[] cPrice= new int [20];
	private int[] pMths = new int[20];
	private int cPayments[]= new int[20];

	  @Override //annotation, guarentees you are actually overriding a method
	  public boolean authenticateUser(User user) {
	   
		  System.out.println(user.getUsername());
	  
	  return true; }
	 

	public void setCarsOwned(Car owned, int cprice, int pmths) {

		if (cOwnedIndex <= cOwnedDB.length)
			cOwnedDB[cOwnedIndex] = owned;
			cPrice[cOwnedIndex] =cprice;
			pMths[cOwnedIndex]=pmths;

		cOwnedIndex++;

	}

	public void setPayments(int payments) {

		cPayments[cOwnedIndex] = payments;
		System.out.println(payments);

	}

	public void setPayments(String[] payaments) {

		/*
		 * for(int i= 0; i<= cPayments.length; i++) { payments = cPayments[i];
		 * System.out.println(payments); }
		 */
	}

	public void getCarsOwned() {

		//Car cDetails = new Car();
		for (int i = 0; i <= cOwnedDB.length; i++) {
			//cDetails = cOwnedDB[i].;

			System.out.println(cOwnedDB[i].getCar()+"   Price: "+cPrice[i]+" Monthly Cost: "+(cPrice[i]/pMths[i]));
		}

	}
	
}
