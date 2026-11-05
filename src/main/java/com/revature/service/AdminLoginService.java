package com.revature.service;

import com.revature.DAOService.DAOService;
import com.revature.DAOService.DataDAO;
import com.revature.cardealer.User;

public class AdminLoginService extends UserLoginService {

    DataDAO cDao = new DAOService();

    public AdminLoginService() {
        super();
    }

    public AdminLoginService(UserAccountRepository userRepository) {
        super(userRepository);
    }
    public AdminLoginService(UserAccountRepository userRepository,
            UserAccountRepository administrativeRepository) {
        super(userRepository, administrativeRepository);
    }

    @Override
    public User registerUser(String username, String password) {
        throw new SecurityException("privileged registration requires authenticated administrator provisioning");
    }

    public void deleteAllUsers() {
        System.out.println("You better be sure you want to do this!!!!!!!");
    }
}
