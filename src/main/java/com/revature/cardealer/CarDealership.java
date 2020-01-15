/**
 * 
 */
package com.revature.cardealer;

import java.io.*;
//import com.revature.service.UserLoginService;
import java.util.Scanner;

import com.revature.DAOService.DAOService;
import com.revature.DAOService.DataDAO;
import com.revature.service.*;

/**
 * @author danew
 *
 */
public class CarDealership {

	private static UserLoginService uls = new UserLoginService();
	private static CustomerLoginService cls = new CustomerLoginService();
	private static EmployeeLoginService els = new EmployeeLoginService();
	// private static AdminLoginService uls = new AdminLoginService();
	private static User customer = new User();
	private static User employee = new User();
	static Offer Carlot = new Offer();
	static Payments PaymentsDB= new Payments();
	
	DataDAO cDao = new DAOService();
	

	private static Scanner scan = new Scanner(System.in);

	public static void main(String[] args) {

		String option = "";

		do {
			System.out.println("Welcome: Car Dealership!!\n");
			System.out.println("[1] Register User");
			System.out.println("[2] Customer Login");
			System.out.println("[3] Employee Login");
			System.out.println("[4] Exit");
			System.out.println("[5] Admin features");

			option = scan.nextLine();
			performUserAction(option);
		} while (!"4".equals(option));

	}

	private static void performUserAction(String option) {

		switch (option) {
		case "1":
			System.out.println("Welcome: Car Dealership!!\n");
			System.out.println("[1] Register Customer");
			System.out.println("[2] Register Employee");
			System.out.println("Select an option:  ");
			option = scan.nextLine();

			if (option.equals("1")) {
				customer = getUserInfo();
				((CustomerLoginService) uls).registerUser(customer.getUsername(), customer.getPassword());
				System.out.println(customer.getUsername());
			} else if (option.equals("2")) {
				employee = getUserInfo();
				((EmployeeLoginService) uls).registerUser(employee.getUsername(), employee.getPassword());
			}

			break;
		case "2":
			uls = new CustomerLoginService();
			if (((CustomerLoginService) uls).authenticateUser(getUserInfo()))
				// if (uls.authenticateUser(getUserInfo())){

				System.out.println("Welcome To The Dealership: \n");
			System.out.println("[1] View Car Lot");
			System.out.println("[2] View Cars Owned");
			System.out.println("[3] View Payments");
			System.out.println("\nSelect an option: ");
			option = scan.nextLine();
			// performUserAction(option);

			if (option.equals("1")) {

				Carlot.registerOffer("Honda ", "Accord ", 2017, 15000, "yes", 7); // Make, Model, year, Price, Avail, Qty
				Carlot.registerOffer("Chevy ", "Malibu ", 2020, 17456, "Yes", 4);
				Carlot.registerOffer("BMW   ", "4Series", 2016, 10456, "Yes", 6);
				Carlot.registerOffer("Toyota", "Corolla", 2014, 13456, "Yes", 3);
				// newOffer.getOffer();
				// System.out.println("Car:- Make: Honda Model:- Accord Year: 2018 Price: 15456
				// Avail: Yes Amt:7");
				// option = scan.nextLine();
				System.out.println("\n\n Enter Offer Number to Request Purchase:");
				option = scan.nextLine();

				int opt = Integer.parseInt(option);
				//customer.getUsername();
				System.out.println(opt);
				if (opt <= Carlot.offerDB.length)
					Carlot.setpOffer(customer.getUsername(), opt); // user.getUsername()

			} else if (option.equals("2")) {
				System.out.println(" Cars Owned:-  \n\n");

				((CustomerLoginService) uls).getCarsOwned();
				
				

			} else if (option.equals("3")) {

				System.out.println(" View Car Payments\n\n");

			}

			else {
				System.out.println("failure");
			}
			break;
		case "3":
			uls = new EmployeeLoginService();
			if (((EmployeeLoginService) uls).authenticateUser(getUserInfo())) {

				System.out.println("Employee View: \n");
				System.out.println("[1] View Car Lot");
				System.out.println("[2] View Pending Requests");
				System.out.println("[3] View Customer Payments");
				System.out.println("\n Select an option: ");
				option = scan.nextLine();

				if (option.equals("1")) {
					System.out.println("Welcome to The Car Dealership");
					Carlot.getOfferAll();
					System.out.println("\n[1] Add Car to Lot: ");
					System.out.println("\n[2] Revome Car Form Lot: ");
					System.out.println("\n Select an option: ");
					option = scan.nextLine();
					if (option.equals("1")) {
						System.out.println("\n Enter -->  Make:   Model:  Year:  Price:  Stock Qty:  <---");
						option = scan.nextLine();
						String[] input = option.split("");
						Carlot.registerOffer(input[0], input[1], Integer.parseInt(input[2]), Integer.parseInt(input[3]),"Yes", Integer.parseInt(input[4]));
					} else 
						if (option.equals("2")) {
						System.out.println("\n Enter CarLot Number  --> [ ] <--: ");
						String input = scan.nextLine();
						Carlot.removeOffer(Integer.parseInt(input));
						
					}		
					else	
					if (option.equals("2")) {
							
							
							System.out.println("\n View Payments:");
							
							System.out.println("Pending Purchase Requests: \n");
							Carlot.getpOffers();

							System.out.println("\nSelect an Offer to Approve: ");
							int anum = Integer.parseInt(scan.nextLine());

							System.out.println("\nEnter Number of Months Customer has to pay: ");
							int mths = Integer.parseInt(scan.nextLine());
							if (mths > 0 && anum > 0) {
								int cprice = Carlot.setAccept(anum, mths, true);
								((CustomerLoginService) uls).setCarsOwned(Carlot.getCarDB(anum), cprice, mths);
								
							} else
								System.out.println("\nMessage: Can Not Accept The Payment terms ");
							System.out.println("\nMessage: Do you Want to REJECT ALL other Offers Pending? [Yes] or [No] :-   ");
							String input = scan.nextLine();
							if(input=="yes"|| input =="y" || input=="Y" || input =="Yes")
								Carlot.rejectAllOffers();

						}

					if (option.equals("3")) {
						System.out.println("[3] View Customer Payments");
						
					}

				} else {
					System.out.println("failure");
				}
			}
			break;
		case "4":
			System.out.println("goodbye");
			break;
		case "5":
			uls = new AdminLoginService();
			System.out.println("You are now an Admin");
			System.out.println("\n [1] Serilaize and Save Data");
			System.out.println("\n [2] DeSerilize and Load Data");
			
			System.out.println("\n Enter Choice: ");
			String input = scan.nextLine();
			if (input=="1") {
				((AdminLoginService) uls).deleteAllUsers();
			}
			else
				if(input=="2")
				{
					// uls.removeUser(getUserInfo());
					// System.out.println("successfully removed user");
				}

			break;
		default:
			System.out.println("did not understand input");
			break;

		}
	}

	/* 1 */

	public static User getUserInfo() {
		User user = new User();
		System.out.println("Enter username:");
		user.setUsername(scan.nextLine());
		System.out.println("Enter password");
		user.setPassword(scan.nextLine());
		return user;
	}

	
	public void loadData() {
		
		//get data
		//remember to normalize object be saving the file. 
		cDao.readData("CarDealer"+".dat").getCustomerLoginService();
		
		
		//remember to normalize for eash Array
		
		
		  try { 
			  	FileInputStream fileIn = new FileInputStream("CarDealer"+".dat");
		  		ObjectInputStream in = new ObjectInputStream(fileIn); //customer= (User)
		  		in.readObject(); //customer= (User) in.readObject(); in.close();
		  		fileIn.close(); 
		  }catch(IOException i) 
		  { 
			  i.printStackTrace(); 
			  return;
		  }catch(ClassNotFoundException c) 
		  	{ 
			  System.out.println("Employee not found");
			  c.printStackTrace(); 
			  return; 
			 }
		 
		
		
	
	}
	
	public void saveData() {
		 		
		Data myData = new Data( cls, els, customer, employee, Carlot, PaymentsDB);
	
		cDao.createData(myData);
		
	}
	
	
	
	
	
	

	/*
	 * public static User getUserInfo(int u) {
	 * 
	 * 
	 * if (u==1) { Customer customer = new Customer();
	 * System.out.println("Enter username:"); customer.setUsername(scan.nextLine());
	 * System.out.println("Enter password"); customer.setPassword(scan.nextLine());
	 * return customer; } else if(u==2) { Employee employee = new Employee();
	 * System.out.println("Enter username:"); employee.setUsername(scan.nextLine());
	 * System.out.println("Enter password"); employee.setPassword(scan.nextLine());
	 * return employee; }
	 * 
	 * 
	 * }
	 * 
	 * 
	 * 
	 * public static Customer getCustomerInfo() { Customer customer = new
	 * Customer(); System.out.println("Enter username:");
	 * customer.setUsername(scan.nextLine()); System.out.println("Enter password");
	 * customer.setPassword(scan.nextLine()); return customer;
	 * 
	 * }
	 * 
	 * 
	 * public static Employee getEmployeeInfo() { Employee employee = new
	 * Employee(); System.out.println("Enter username:");
	 * employee.setUsername(scan.nextLine()); System.out.println("Enter password");
	 * employee.setPassword(scan.nextLine()); return employee; }
	 */

}
