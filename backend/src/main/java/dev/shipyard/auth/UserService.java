package dev.shipyard.auth;

import dev.shipyard.common.BadRequestException;
import dev.shipyard.common.ConflictException;
import dev.shipyard.common.Ids;
import dev.shipyard.common.Time;
import dev.shipyard.config.PlatformProperties;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService implements UserDetailsService {
    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final PlatformProperties props;

    public UserService(UserRepository users, PasswordEncoder encoder, PlatformProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        User u = users.findByUsername(username).orElseThrow(() -> new UsernameNotFoundException(username));
        return new org.springframework.security.core.userdetails.User(u.username(), u.passwordHash(),
                List.of(new SimpleGrantedAuthority("ROLE_" + u.role())));
    }

    public boolean setupRequired() {
        return users.count() == 0;
    }

    /** Creates the first administrator. Only allowed while no users exist. */
    public synchronized User createInitialAdmin(String username, String password) {
        if (!setupRequired()) throw new ConflictException("Platform is already set up");
        validate(username, password);
        User u = new User(Ids.uuid(), username.strip(), encoder.encode(password), "ADMINISTRATOR", Time.now());
        users.insert(u);
        return u;
    }

    public void changePassword(String username, String current, String next) {
        User u = users.findByUsername(username).orElseThrow();
        if (!encoder.matches(current, u.passwordHash())) throw new BadRequestException("Current password is incorrect");
        validate(username, next);
        users.updatePassword(u.id(), encoder.encode(next));
    }

    @EventListener(ApplicationReadyEvent.class)
    void bootstrapAdmin() {
        if (setupRequired() && props.initialAdminUsername() != null && props.initialAdminPassword() != null
                && !props.initialAdminPassword().isBlank()) {
            createInitialAdmin(props.initialAdminUsername(), props.initialAdminPassword());
            log.info("Created initial administrator '{}' from configuration", props.initialAdminUsername());
        }
    }

    private static void validate(String username, String password) {
        if (username == null || !username.strip().matches("[A-Za-z0-9._-]{3,64}")) {
            throw new BadRequestException("Username must be 3-64 characters: letters, digits, '.', '_' or '-'");
        }
        if (password == null || password.length() < 10) {
            throw new BadRequestException("Password must be at least 10 characters");
        }
    }
}
