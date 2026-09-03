-- users table
CREATE TABLE users (
    id UUID PRIMARY KEY,
    github_id BIGINT UNIQUE NOT NULL,
    github_username VARCHAR(100) UNIQUE NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    avatar_url VARCHAR(200),
    access_token TEXT,
    token_scopes VARCHAR(500),
    created_at TIMESTAMP(6) WITH TIME ZONE
);

-- repositories table
CREATE TABLE repositories (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    github_repo_id BIGINT NOT NULL,
    owner VARCHAR(100) NOT NULL,
    name VARCHAR(200) NOT NULL,
    full_name VARCHAR(300) NOT NULL,
    is_private BOOLEAN NOT NULL,
    default_branch VARCHAR(100) NOT NULL,
    language VARCHAR(100),
    html_url VARCHAR(500),
    description TEXT,
    index_status VARCHAR(20) NOT NULL,
    indexed_at TIMESTAMP(6) WITH TIME ZONE,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    files_total INTEGER NOT NULL DEFAULT 0,
    files_processed INTEGER NOT NULL DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_repositories_user_github UNIQUE (user_id, github_repo_id)
);

-- chat_sessions table
CREATE TABLE chat_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    repository_id UUID NOT NULL,
    title VARCHAR(200) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- chat_messages table
CREATE TABLE chat_messages (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    citations TEXT,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
