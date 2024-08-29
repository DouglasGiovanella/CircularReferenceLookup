package com.asaas.service.userdetails

import com.asaas.userdetails.CustomUserDetails
import grails.plugin.springsecurity.userdetails.GormUserDetailsService
import grails.plugin.springsecurity.userdetails.GrailsUser
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

class CustomUserDetailsService extends GormUserDetailsService {

    @Override
    protected UserDetails createUserDetails(user, Collection<GrantedAuthority> authorities) {
        GrailsUser grailsUser = (GrailsUser) super.createUserDetails(user, authorities)
        return new CustomUserDetails(grailsUser)
    }
}
