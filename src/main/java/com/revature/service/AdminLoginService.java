package com.revature.service;

import com.revature.cardealer.*;
import com.revature.DAOService.DAOService;
import com.revature.DAOService.DataDAO;



public class AdminLoginService extends UserLoginService{
	
	

	DataDAO cDao = new DAOService();
	
	@Override //annotation, guarentees you are actually overriding a method
	public boolean authenticateUser(User user) {
		return true;
	}
	
	public void deleteAllUsers() {
		System.out.println("You better be sure you want to do this!!!!!!!");
	}
	
	
	
	
	//mDao.createMap(myMap);
	 
	
} 