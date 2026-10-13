package com.interview;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;


@Configuration
class SecurityConfig {
    @Bean UserDetailsService users(Environment env) {
        String password = env.getProperty("APP_PASSWORD", "");
        if (password.length() < 16 || password.length() > 64 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("请设置 APP_PASSWORD：16～64 个字符的个人登录密码。");
        }
        return new InMemoryUserDetailsManager(User.withUsername(env.getProperty("APP_USERNAME", "owner"))
            .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("OWNER").build());
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/healthz", "/login.html", "/login.js", "/style.css", "/api/csrf", "/error").permitAll()
                .anyRequest().authenticated())
            .formLogin(form -> form.loginPage("/login.html").loginProcessingUrl("/login")
                .defaultSuccessUrl("/", true).failureUrl("/login.html?error").permitAll())
            .logout(logout -> logout.logoutSuccessUrl("/login.html?logout"))
            .exceptionHandling(errors -> errors.authenticationEntryPoint((req, res, ex) -> {
                if (!req.getRequestURI().startsWith(req.getContextPath()+"/api/")) { res.sendRedirect(req.getContextPath()+"/login.html"); return; }
                res.setStatus(401); res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"error\":\"登录已过期，请重新登录。输入已保留。\"}");
            }))
            .headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")));
        // CSRF stays enabled. Frontend gets its session token from /api/csrf.
        return http.build();
    }
}
