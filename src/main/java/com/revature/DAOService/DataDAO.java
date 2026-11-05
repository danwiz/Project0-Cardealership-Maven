package com.revature.DAOService;

import com.revature.cardealer.Data;

public interface DataDAO {

    SaveResult saveData(Data data, String filename);

    LoadResult loadData(String filename);
}
