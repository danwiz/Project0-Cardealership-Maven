package com.revature.DAOService;  // consider doaService as better package name - change late

//import src.com.revature.dao;
import com.revature.cardealer.Data;

public interface DataDAO {
	
	public void createData(Data d);
	
	public Data readData(String caption);

}



