

```markdown
# RepoLens — AI-Powered GitHub Repository Assistant

RepoLens connects GitHub repositories to an AI-powered code Q&A workflow. Its Spring Boot backend handles GitHub authentication, repository and file selection, asynchronous code indexing, vector search, and AI chat grounded in repository content.

## Contribution Scope

This project focuses on a Spring Boot backend implementation for an AI-powered GitHub repository assistant.

My primary contributions include:
- Backend architecture and REST API development
- GitHub OAuth integration and repository synchronization
- Repository indexing pipeline and asynchronous processing
- RAG workflow with embeddings and vector search
- Authentication, persistence, and streaming chat APIs

The `client/` directory contains a Next.js frontend used as an interface to demonstrate and test the backend functionality.



## Features

- GitHub OAuth sign-in, with access to authorized public and private repositories.
- Repository synchronization and selectable-file indexing.
- Asynchronous indexing with progress and status tracking.
- Filtering of unsupported, generated, and oversized files.
- Code chunking, Ollama embeddings, and vector storage in PostgreSQL with pgvector.
- Repository-scoped semantic retrieval for code questions.
- AI responses streamed over Server-Sent Events (SSE).
- Persistent chat sessions and messages, with source-file citations.
- Encrypted storage of GitHub access tokens and repository ownership checks.

## Backend flow

1. A user signs in with GitHub, and the backend stores the access token encrypted.
2. The backend retrieves the user's repositories and their file trees from GitHub.
3. Selected eligible files are fetched, split into chunks, embedded, and stored in pgvector with repository metadata.
4. For a chat question, the backend searches only the selected repository's vectors and supplies matching code context to the language model.
5. The generated response is streamed to the client as SSE events and saved with its citations.

## Technology

- **Backend:** Java, Spring Boot, Spring Security, Spring Data JPA, Spring AI
- **AI and retrieval:** Ollama (`qwen3:4B` chat model and `nomic-embed-text:latest` embedding model), RAG
- **Database:** PostgreSQL, pgvector
- **GitHub integration:** GitHub OAuth and GitHub REST API
- **Client included in repository:** Next.js, React, TypeScript
- **Local database setup:** Docker Compose

## API overview

Protected `/api/**` endpoints require an authenticated session.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/api/auth/login-url` | Returns the GitHub OAuth authorization path |
| `GET` | `/api/auth/me` | Returns the authenticated user's profile |
| `POST` | `/api/auth/logout` | Ends the current session |
| `GET` | `/api/repos?refresh=true` | Synchronizes and lists the user's GitHub repositories |
| `GET` | `/api/repos/{id}` | Gets a repository owned by the current user |
| `GET` | `/api/repos/{id}/tree` | Lists files available for indexing |
| `POST` | `/api/repos/{id}/index` | Starts asynchronous indexing; optionally accepts selected paths |
| `GET` | `/api/repos/{id}/status` | Gets indexing status and progress |
| `POST` | `/api/chat/sessions` | Creates a chat session for an indexed repository |
| `GET` | `/api/chat/sessions?repositoryId={id}` | Lists sessions for a repository |
| `GET` | `/api/chat/sessions/{id}` | Retrieves a session's messages |
| `POST` | `/api/chat/sessions/{id}/messages` | Sends a question and streams the response over SSE |

The chat stream emits `user_message`, `token`, `assistant_message`, and `done` events.

## Run locally

### Prerequisites

- Java 26
- Docker with Docker Compose
- Ollama
- Node.js and npm, if you want to run the included client
- A GitHub OAuth App

### Configuration

Create a root-level `.env` file and configure the values used by the backend and Docker Compose. Do not commit this file or put real credentials in the repository.

```dotenv
GITHUB_CLIENT_ID=your-github-oauth-client-id
GITHUB_CLIENT_SECRET=your-github-oauth-client-secret

DB_USERNAME=postgres
DP_PASSWORD=your-local-database-password

TOKEN_ENCRYPTOR_PASSWORD=use-a-long-random-secret
TOKEN_ENCRYPTOR_SALT=use-a-valid-hexadecimal-salt
```

Configure the GitHub OAuth App callback URL as:

```text
http://localhost:8080/login/oauth2/code/github
```

The application defaults to PostgreSQL at `localhost:5433`, Ollama at `http://localhost:11434`, and the frontend at `http://localhost:3000`. If you change these defaults, update the corresponding backend configuration and OAuth redirect settings.

### Start PostgreSQL

From the repository root:

```bash
docker compose up -d postgres
```

### Start Ollama models

Make sure Ollama is running, then download the models configured by the backend:

```bash
ollama pull qwen3:4B
ollama pull nomic-embed-text:latest
```

### Start the backend

From the `backend/` directory:

```powershell
.\mvnw.cmd spring-boot:run
```

The backend runs at `http://localhost:8080`.

### Run the included client (optional)

The client is a demonstration interface. From the `client/` directory:

```bash
npm install
npm run dev
```

The client runs at `http://localhost:3000` and uses the backend at `http://localhost:8080` by default.

## Project structure

```text
backend/   Spring Boot API and backend services
client/    Next.js demonstration client
docker/    PostgreSQL extension initialization
```

## Notes

- Repository content is fetched from GitHub using the signed-in user's authorization.
- Indexing is asynchronous; use the repository status endpoint to check progress.
- The application uses local Ollama models and a local PostgreSQL/pgvector database by default.
- Keep OAuth credentials, encryption secrets, and database passwords out of source control.
```

