# data/repository/

Repositories bridging data sources to domain — this is the UI-developer surface.

- `AuthRepository` / `AuthRepositoryImpl` — register, login, cold-start restore, starts `MessagingRuntime`.
- `ChatsRepository` / `ChatsRepositoryImpl` — Room-backed chat list (replaces `MockChatsRepository` in DI).
- `MessagesRepository` / `MessagesRepositoryImpl` — observe 1:1 transcript + send.
- `ContactsRepository` / `ContactsRepositoryImpl` — mint/accept/revoke device-minted invites, Room contacts.
