package com.viyangle.study_tour.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.viyangle.study_tour.exception.ForbiddenException;
import com.viyangle.study_tour.exception.UnauthorizedException;
import com.viyangle.study_tour.mapper.AccountMapper;
import com.viyangle.study_tour.mapper.AccountTagPrefMapper;
import com.viyangle.study_tour.mapper.LeaderProfileMapper;
import com.viyangle.study_tour.pojo.Account;
import com.viyangle.study_tour.pojo.LoginRequest;
import com.viyangle.study_tour.pojo.RegisterRequest;
import com.viyangle.study_tour.pojo.UpdateAccountProfileRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceImplSecurityTest {
    @Mock private AccountMapper accountMapper;
    @Mock private AccountTagPrefMapper accountTagPrefMapper;
    @Mock private LeaderProfileMapper leaderProfileMapper;
    @Mock private BCryptPasswordEncoder passwordEncoder;
    @InjectMocks private AccountServiceImpl service;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "LEADER", "BOTH", "Both", " leader "})
    void publicRegistrationPreservesChosenBusinessRole(String requestedRole) {
        RegisterRequest request = new RegisterRequest("新用户", "13800138000", "password",
                "password", requestedRole, "320100");
        when(passwordEncoder.encode("password")).thenReturn("encoded");
        doAnswer(invocation -> {
            invocation.<Account>getArgument(0).setId(10L);
            return 1;
        }).when(accountMapper).insert(any(Account.class));

        assertEquals(10L, service.register(request));

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountMapper).insert(captor.capture());
        String expectedRole = requestedRole.trim().toUpperCase(java.util.Locale.ROOT);
        assertEquals(expectedRole, captor.getValue().getRole());
        assertEquals("encoded", captor.getValue().getPasswordHash());
        if ("USER".equals(expectedRole)) {
            verifyNoInteractions(leaderProfileMapper);
        } else {
            var profile = ArgumentCaptor.forClass(com.viyangle.study_tour.pojo.LeaderProfile.class);
            verify(leaderProfileMapper).insert(profile.capture());
            assertEquals(10L, profile.getValue().getAccountId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "LEADER", "BOTH", "Both"})
    void ordinaryUserCanChangeOwnBusinessRole(String requestedRole) {
        authenticate(1L, "USER");
        Account account = new Account();
        account.setId(1L);
        when(accountMapper.selectById(1L)).thenReturn(account);
        service.updateRole(1L, requestedRole);
        var update = ArgumentCaptor.forClass(Account.class);
        verify(accountMapper).updateById(update.capture());
        assertEquals(requestedRole.toUpperCase(java.util.Locale.ROOT), update.getValue().getRole());
        if (!"USER".equals(requestedRole)) {
            verify(leaderProfileMapper).insert(any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", " admin "})
    void publicRegistrationCannotGrantAdmin(String requestedRole) {
        RegisterRequest request = new RegisterRequest("用户", "13800138000", "password",
                "password", requestedRole, "320100");
        assertThrows(ForbiddenException.class, () -> service.register(request));
        verifyNoInteractions(accountMapper, leaderProfileMapper, passwordEncoder);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", " "})
    void registrationRejectsMissingOrUnknownRole(String requestedRole) {
        RegisterRequest request = new RegisterRequest("用户", "13800138000", "password",
                "password", requestedRole, "320100");
        assertThrows(IllegalArgumentException.class, () -> service.register(request));
        verifyNoInteractions(accountMapper, leaderProfileMapper, passwordEncoder);
    }

    @Test
    void ordinaryUserCannotGrantAdminOrChangeAnotherUsersRole() {
        authenticate(1L, "USER");
        assertThrows(ForbiddenException.class, () -> service.updateRole(1L, "ADMIN"));
        assertThrows(ForbiddenException.class, () -> service.updateRole(2L, "LEADER"));
        verifyNoInteractions(accountMapper, leaderProfileMapper);
    }

    @Test
    void unauthenticatedCallerCannotAssignRole() {
        assertThrows(UnauthorizedException.class, () -> service.updateRole(1L, "ADMIN"));
        verifyNoInteractions(accountMapper);
    }

    @ParameterizedTest
    @ValueSource(strings = {"LEADER", "ADMIN"})
    void adminCanAssignRoleToAnotherAccount(String role) {
        authenticate(9L, "ADMIN");
        Account account = new Account();
        account.setId(1L);
        when(accountMapper.selectById(1L)).thenReturn(account);
        service.updateRole(1L, role);
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(accountMapper).updateById(captor.capture());
        assertEquals(role, captor.getValue().getRole());
        if ("LEADER".equals(role)) {
            verify(leaderProfileMapper).insert(any());
        }
    }

    @Test
    void userCanSubmitOwnProfileStatusButCannotChangeAnotherUsersProfile() {
        authenticate(1L, "USER");
        UpdateAccountProfileRequest request = new UpdateAccountProfileRequest();
        request.setStatus(1);
        assertThrows(ForbiddenException.class, () -> service.updateProfile(2L, request));
        verifyNoInteractions(accountMapper);
        Account account = new Account();
        account.setId(1L);
        when(accountMapper.selectById(1L)).thenReturn(account);
        service.updateProfile(1L, request);
        var update = ArgumentCaptor.forClass(Account.class);
        verify(accountMapper).updateById(update.capture());
        assertEquals(1, update.getValue().getStatus());
    }

    @Test
    void disabledAccountCannotLogIn() {
        Account account = new Account();
        account.setStatus(0);
        when(accountMapper.selectByPhone("13800138000")).thenReturn(account);
        LoginRequest request = new LoginRequest();
        request.setPhone("13800138000");
        request.setPassword("password");
        assertNull(service.login(request));
        verifyNoInteractions(passwordEncoder);
    }

    @Test
    void accountResponsesNeverSerializePasswordHash() throws Exception {
        Account account = new Account();
        account.setId(1L);
        account.setPasswordHash("private-hash");
        String json = new ObjectMapper().writeValueAsString(account);
        assertFalse(json.contains("passwordHash"));
        assertFalse(json.contains("private-hash"));
        assertTrue(json.contains("\"id\":1"));
    }

    private void authenticate(Long id, String role) {
        var authentication = new UsernamePasswordAuthenticationToken(id.toString(), null, List.of());
        authentication.setDetails(Map.of("accountId", id, "role", role));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
