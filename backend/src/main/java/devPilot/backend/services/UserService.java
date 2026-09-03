package devPilot.backend.services;

import devPilot.backend.entity.User;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {
    public final UserRepository userRepository;
    public final TextEncryptor textEncryptor;

    @Transactional(readOnly = true)
    public User requiredById(UUID id) {
        return userRepository.findById(id).orElseThrow(() -> new NotFoundException("User not found"));
    }

    public String decryptAccessToken(User user) {
        return textEncryptor.decrypt(user.getAccessToken());
    }

    private static Long toLong(Object value){
        if (value instanceof Number) {
            return ((Number) value).longValue();
        } else if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Cannot convert value to Long: " + value, e);
            }
        } else {
            throw new IllegalArgumentException("Cannot convert value to Long: " + value);
        }
    }

    @Transactional
    public User upsertFromGitHub(Map<String, Object> attributes, String accessToken, String scopes) {
        Long githubId = toLong(attributes.get("id"));
        String login = String.valueOf(attributes.get("login"));
        String name = attributes.get("name") != null
                ? String.valueOf(attributes.get("name"))
                : login;
        String avatarUrl = attributes.get("avatar_url") != null
                ? String.valueOf(attributes.get("avatar_url"))
                : null;

        String encryptedToken = textEncryptor.encrypt(accessToken);

        // GitHub usernames can be renamed and later reclaimed by a different account. If a
        // stale local row still holds this username under a different github_id, free it up
        // first so the unique constraint doesn't reject this (now-legitimate) owner's login.
        userRepository.findByGithubUsername(login)
                .filter(existing -> !existing.getGithubId().equals(githubId))
                .ifPresent(stale -> {
                    stale.setGithubUsername(login + "-stale-" + stale.getGithubId());
                    userRepository.save(stale);
                });

        User user = userRepository.findByGithubId(githubId).orElseGet(User::new);
        user.setGithubId(githubId);
        user.setGithubUsername(login);
        user.setDisplayName(name);
        user.setAvatarUrl(avatarUrl);
        user.setAccessToken(encryptedToken);
        user.setTokenScopes(scopes);
        return userRepository.save(user);
    }
}
