package com.cl.agent.controller;

import com.cl.agent.biz.IUserBiz;
import com.cl.agent.dto.LoginRequest;
import com.cl.agent.model.User;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@CrossOrigin(origins = "*")
public class UserController {

    @Autowired
    private IUserBiz userBiz;

    @PostMapping("/login")
    public ResponseEntity<User> login(@RequestBody LoginRequest request) {
        User user = userBiz.login(request);
        return ResponseEntity.ok(user);
    }
}
