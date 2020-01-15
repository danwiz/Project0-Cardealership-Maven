package com.revature.service;

import com.revature.cardealer.User;

//import com.revature.cardealer.Employee;


public class EmployeeLoginService extends UserLoginService{


	@Override //annotation, guarentees you are actually overriding a method
	public boolean authenticateUser(User user) {
		
		
		return true;
	}
		
		
		
	}


