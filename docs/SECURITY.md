# Security

## Positioning

This repository is:

- a self-hosted personal chat template
- an experimental E2EE starter
- a reference implementation

This repository is not:

- a professionally audited secure messenger
- a guarantee of confidentiality, metadata resistance, or endpoint security
- a drop-in replacement for security-focused production messengers

## Practical Caveats

- Secrets are only safe if your server, devices, and build pipeline are safe.
- Push notifications and mobile OS behavior may leak metadata or affect availability.
- Compromised endpoints bypass transport or storage protections.
- The project has not completed an external security review.

## Before Real Use

- Replace every placeholder secret
- Use HTTPS everywhere
- Use your own Firebase credentials
- Use your own signing keys
- Rotate any credential that was previously exposed or reused
- Review the code yourself before trusting it with sensitive conversations
