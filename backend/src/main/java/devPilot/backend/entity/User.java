package devPilot.backend.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Table(name = "users")
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "github_id", unique = true, nullable = false)
    private Long githubId;

    @Column(name = "github_username", unique = true, nullable = false, length = 100)
    private String githubUsername;

    @Column(name = "display_name", length = 100, nullable = false)
    private String displayName;

    @Column(name = "avatar_url", length = 200)
    private String avatarUrl;

    @Column(name = "access_token", length = 200, columnDefinition = "TEXT")
    private String accessToken;

    @Column(name = "token_scopes", length = 500)
    private String tokenScopes;
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }
}
