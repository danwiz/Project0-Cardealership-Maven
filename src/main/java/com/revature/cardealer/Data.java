package com.revature.cardealer;

import java.io.Serializable;

import com.revature.service.*;


/*
 * Serializable is a Marker Interface
 * Marker Interface - Interface with no abstract methods
 * It works as check for the compiler.
 */

public class Data implements Serializable {
		
		private EmployeeLoginService dEls;
		private CustomerLoginService dCls;
	    private User dCustomer;
	    private User dEmployee;
	    private Offer dCarlot;
	    private Payments dPayments;
	    String caption;
	    /*
	     * private User customer ; private User employee ; private Offer Carlot ;
	     */
	    
	    public void setCustomerLoginService( CustomerLoginService dcls){
	    	dCls= dcls;	
	    }
	    public void setEmployeeLoginService( EmployeeLoginService dels){
	    	dEls=dels;	
	    }

	    public EmployeeLoginService getEmployeeLoginService() {	
			return dEls;
		}
	    public CustomerLoginService getCustomerLoginService() {			
			return dCls;
		}

		public void setOffer(Offer carlot) {
			dCarlot = carlot;
		}

		public User getCustomer() {
			return dCustomer;
		}

		public void setCustomer(User customer) {
			this.dCustomer = customer;
		}

		public User getEmployee() {
			return dEmployee;
		}

		public void setEmployee(User employee) {
			dEmployee = employee ;
		}

		public String getCaption() {
			return caption;
		}

		public void setCaption(String caption) {
			this.caption = caption;
		}

		@Override
		public String toString() {
			return "[ Data Objects ]:    Logins:- =" + "   Customer:  "+dCls.getUserNames().toString() + " Employee: -   "+dEls.getUserNames().toString()+ 
				   "\nCars:-   "+ dCarlot.offerDB.toString()+ "\nCustomer Payments:   "+dPayments.getpPayment();
		}

		public Data() {
			super();
		}

		public Data(CustomerLoginService dcls, EmployeeLoginService dels, User duser, User cuser, Offer dcarlot, Payments dpayments) {
			super();
			this.dCls = dcls;
			this.dEls = dels;
			this.dCustomer = cuser;
			this.dEmployee = duser;
		}

}
