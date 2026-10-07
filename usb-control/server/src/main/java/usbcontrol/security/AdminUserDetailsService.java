package usbcontrol.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import usbcontrol.domain.Admin;
import usbcontrol.domain.AdminRepository;

import java.time.LocalDateTime;

@Service
public class AdminUserDetailsService implements UserDetailsService {

    private final AdminRepository admins;

    public AdminUserDetailsService(AdminRepository admins) {
        this.admins = admins;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        Admin admin = admins.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        return User.withUsername(admin.getUsername())
                .password(admin.getPasswordHash())
                .accountLocked(admin.isLocked(LocalDateTime.now()))
                .roles("ADMIN")
                .build();
    }
}
