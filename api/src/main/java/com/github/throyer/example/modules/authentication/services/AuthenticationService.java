package com.github.throyer.example.modules.authentication.services;

import static com.github.throyer.example.modules.infra.constants.MessagesConstants.USER_NOT_FOUND_MESSAGE;
import static com.github.throyer.example.modules.infra.http.Responses.notFound;
import static com.github.throyer.example.modules.shared.utils.InternationalizationUtils.message;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.github.throyer.example.modules.authentication.models.Authorized;
import com.github.throyer.example.modules.users.repositories.UserRepository;

@Service
public class AuthenticationService implements UserDetailsService {

  private final UserRepository userRepository;

  @Autowired
  public AuthenticationService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  @Override
  public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
    return userRepository.findByEmail(email)
      .map(Authorized::new)
        .orElseThrow(() -> notFound(message(USER_NOT_FOUND_MESSAGE)));
  }
}
