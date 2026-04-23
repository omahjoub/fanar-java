# Security Policy

## Reporting a Vulnerability

**Do not open a public issue for security vulnerabilities.** Please use one of these private channels:

1. **GitHub Private Vulnerability Reporting** (preferred) — open a private advisory via the repository's
   *Security → Report a vulnerability* tab. Provides end-to-end confidentiality and an auditable trail.
2. **Email** — `mahjoub.oussama@gmail.com` with the subject line `[SECURITY] fanar-java: <brief description>`.

Include:

- Description of the vulnerability
- Steps to reproduce
- Impact assessment
- Suggested fix (if any)

We will acknowledge receipt within 48 hours and provide a timeline for resolution.

## Supported Versions

The SDK is in **pre-1.0 / design phase**. Until 1.0 ships:

- Only the **latest release** receives security fixes.
- API may change between minor versions (0.x.y → 0.x+1.0); see `docs/JAVA_LIBRARY_BEST_PRACTICES.md` (JLBP-3, JLBP-12) for the stability policy.

Once 1.0 ships, the policy below applies:

| Version        | Supported           |
|----------------|---------------------|
| Latest release | Yes                 |
| Previous minor | Security fixes only |
| Older          | No                  |
