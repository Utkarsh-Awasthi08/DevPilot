-- Foreign keys were missing entirely: user_id/repository_id/session_id were plain UUID
-- columns with no REFERENCES, so the DB accepted orphaned rows and deleting a parent left
-- its dependents behind. ON DELETE CASCADE matches the app's ownership model: deleting a
-- user should remove their repos/sessions/messages, deleting a repo should remove its
-- sessions/messages, deleting a session should remove its messages.
ALTER TABLE repositories
    ADD CONSTRAINT fk_repositories_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE chat_sessions
    ADD CONSTRAINT fk_chat_sessions_user
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE;

ALTER TABLE chat_sessions
    ADD CONSTRAINT fk_chat_sessions_repository
    FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE CASCADE;

ALTER TABLE chat_messages
    ADD CONSTRAINT fk_chat_messages_session
    FOREIGN KEY (session_id) REFERENCES chat_sessions (id) ON DELETE CASCADE;

-- Indexes on the columns "list this user's sessions" / "load this session's messages"
-- actually filter on — previously only primary keys and the one unique constraint existed.
CREATE INDEX idx_chat_sessions_user_id ON chat_sessions (user_id);
CREATE INDEX idx_chat_sessions_repository_id ON chat_sessions (repository_id);
CREATE INDEX idx_chat_messages_session_id ON chat_messages (session_id);
