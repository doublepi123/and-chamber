# AGENTS.md

## Privacy (mandatory)

This repository is public. Never commit or push the maintainers' private information.

- Forbidden in code, tests, docs, codemaps, commit messages, branch names, PR/issue text and CI config: real personal or self-hosted domains and hostnames, IP addresses, tunnel/Access team names, account names, emails, tokens, passwords, keys, local paths that identify a person, and screenshots or logs containing any of these.
- Use placeholders instead: `example.com` / `openchamber.example.com`, `team.cloudflareaccess.com`, `192.0.2.x` (RFC 5737), `user@example.com`.
- A real address the user mentions in chat is context only. Never copy it into the repository.
- Exception for Git identity metadata: a maintainer-approved author/committer name and email may be used in Git commit metadata, including the maintainer's own email. Do not require a GitHub noreply address or change the existing identity merely because it is not a noreply address. This exception does not permit copying the identity into source files, commit messages, release notes or any other content listed above.
- Do not change global Git identity settings or rewrite existing commit metadata unless the maintainer explicitly requests it.
- Before every commit and push, review the full staged diff and the commit message (`git diff --cached`, `git log -p <upstream>..HEAD`) for the items above, and grep for any private value mentioned in the conversation. If anything leaks, fix it before pushing.
- If a leak was already pushed, stop and tell the user immediately: do not try to hide it, and do not rewrite shared history without their consent.

## Repository Map

A full codemap is available at `codemap.md` in the project root.

Before working on any task, read `codemap.md` to understand:
- Project architecture and entry points
- Directory responsibilities and design patterns
- Data flow and integration points between modules

For deep work on a specific folder, also read that folder's `codemap.md`.
