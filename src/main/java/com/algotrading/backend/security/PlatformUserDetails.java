package com.algotrading.backend.security;

import com.algotrading.backend.model.PlatformUser;
import lombok.Getter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Getter
public class PlatformUserDetails implements UserDetails {

    private final PlatformUser user;

    public PlatformUserDetails(PlatformUser user) {
        this.user = user;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
    }

    @Override public String getPassword()   { return user.getPasswordHash(); }
    @Override public String getUsername()   { return user.getUsername(); }
    @Override public boolean isEnabled()    { return user.isEnabled(); }
    @Override public boolean isAccountNonExpired()    { return true; }
    @Override public boolean isAccountNonLocked()     { return user.isEnabled(); }
    @Override public boolean isCredentialsNonExpired(){ return true; }

    public int       getMaxLotSize()    { return user.getMaxLotSize(); }
    public LocalDate getPlanExpiryDate(){ return user.getPlanExpiryDate(); }
    public boolean   isPlanActive()     { return user.isPlanActive(); }
}
