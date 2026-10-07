package usbcontrol.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import usbcontrol.service.AuditService;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, AuditService audit) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // PC 프로그램은 로그인 대신 비밀 키로 확인합니다 (AgentApiController)
                        .requestMatchers("/api/agent/**").permitAll()
                        .requestMatchers("/login", "/setup", "/style.css").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/agent/**"))
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true)
                        .failureHandler((request, response, exception) -> {
                            String reason = exception instanceof LockedException
                                    ? "locked" : "bad";
                            response.sendRedirect(request.getContextPath() + "/login?error=" + reason);
                        }))
                .logout(logout -> logout
                        .logoutSuccessHandler((request, response, authentication) -> {
                            if (authentication != null) {
                                audit.log(authentication.getName(), "로그아웃", "");
                            }
                            response.sendRedirect(request.getContextPath() + "/login?logout");
                        }))
                .headers(headers -> headers.frameOptions(frame -> frame.deny()));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
