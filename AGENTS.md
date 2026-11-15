# Agent instructions

## Skills: check before every task

Before starting any non-trivial task, (1) check skills already available in this repo and environment; (2) if none fits, run `npx -y skills find <keywords>` (skills.sh) and use the find-skills skill; (3) vet any hit (prefer 1K+ installs, reputable owner, read SKILL.md and scripts; reject anything asking for secrets or running opaque downloads); (4) apply it, installing with `npx -y skills add <owner/repo@skill> -y` if it will be reused; (5) if nothing fits, proceed normally. Installing a skill never authorizes deploys, publishing, spending or sending messages.
