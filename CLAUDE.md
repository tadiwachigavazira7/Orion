# CLAUDE.md

# Orion Development Guide

This file defines the standards, architecture, and conventions for the Orion project. Follow these guidelines unless explicitly instructed otherwise.

---

# Project Overview

Orion is a production-quality mobile application built with React Native, TypeScript, and Supabase.

Priorities, in order:

1. Security
2. Correctness
3. Maintainability
4. Readability
5. Performance
6. Developer experience

Never sacrifice security for convenience.

# Rule Priority

If rules conflict, follow this priority order:

1. Security
2. Database integrity (RLS, data correctness)
3. Architecture consistency
4. Business logic correctness
5. Maintainability
6. Performance
7. Developer convenience

---

# Tech Stack

Frontend
- React Native
- Expo
- TypeScript

Backend
- Supabase
- PostgreSQL
- Row Level Security (RLS)
- Supabase Auth
- Supabase Storage
- Edge Functions when appropriate

Development
- Claude Code
- Git
- npm

---

# Architecture

Architecture style:
- Layered monolith
- Clean architecture principles (lightweight, not dogmatic)

Core principle:
Keep UI, business logic, and data access separated — but avoid unnecessary abstraction.

Application flow:

UI (Screens / Components)
→ Hooks / Services (business logic)
→ Supabase client (data access)
→ PostgreSQL (database)

Guidelines:
- Business logic should NOT live in UI components
- Not every function needs its own abstraction layer
- Avoid creating services unless logic is reused or complex

---

# Before Writing Data-Dependent Code

Before implementing any UI that displays, creates, or modifies data:

1. Locate the relevant table(s) in `supabase/migrations`. Do not assume a schema — read it.
2. If the table isn't visible in context, search for it before writing code. Do not guess column names or write UI around an assumed shape of the data.
3. If a required table or relationship doesn't exist yet, say so explicitly and propose a migration rather than silently working around it with hardcoded or placeholder content.
4. Check for an existing fetch/service pattern for similar data (e.g. how a sibling feature loads its list) and follow it rather than inventing a new pattern.

---

# Data Fetching & Empty State Standards

Every list, table, or "empty state" UI must be backed by a real query — never a hardcoded placeholder standing in for logic that hasn't been implemented yet.

Each screen or component with dynamic content must handle three distinct states explicitly:

- **Loading** — request in flight
- **Error** — request failed
- **Empty** — request succeeded and returned zero rows

Do not:
- Render static "No X yet" copy without first attempting a real fetch
- Leave one section of a screen wired to real data while a sibling section (same file, same pattern) is left static — this asymmetry is a strong signal of incomplete implementation and should be treated as a bug, not a stylistic choice

If a data source genuinely isn't available yet (e.g. backend not built), say so explicitly in code comments and in your explanation to the user — don't disguise it as a finished empty state.

---

# General Coding Standards

Always use TypeScript. Avoid `any` without a documented reason.

Prefer explicit typing and self-documenting code.

Keep functions focused on one responsibility.

Prefer composition over unnecessary abstraction.

Avoid duplicated code — reuse existing services/hooks rather than reimplementing similar logic.

---

# React Standards

Prefer functional components and hooks.

Avoid unnecessary re-renders.

Keep components focused; move complex or business logic into hooks or services, not the component body.

---

# Folder Organization

Keep code organized by feature whenever practical.

Typical structure:

```
src/
    components/
    screens/
    hooks/
    services/
    lib/
    types/
    utils/
    constants/
```

Database migrations belong inside `supabase/migrations`.

---

# Database Standards

Every user-owned table must:

- Reference `auth.users(id)`
- Have Row Level Security enabled
- Have appropriate RLS policies (test both allowed and denied access)
- Use UUID primary keys
- Include `created_at`
- Include `updated_at` when appropriate

Never disable RLS. Never create public access unless explicitly required.

Always use migrations for schema changes — avoid manual dashboard edits.

When adding a migration, also flag any UI or service code that should now be updated to use it (don't leave new tables unconnected to the app).

---

# Security

Security is the highest priority.

Never expose service role keys, secrets, API keys, or private credentials.

Client-side environment variables in Expo must use the `EXPO_PUBLIC_` prefix intentionally and only for values safe to ship to the client. Never put secrets in an `EXPO_PUBLIC_` variable. Never commit `.env` files.

Validate all user input. Use least privilege. Never bypass RLS. Always assume client-side data can be manipulated.

---

# Performance

Avoid unnecessary database queries. Select only required columns. Avoid N+1 query patterns. Use indexes where appropriate.

Optimize only when there's evidence it's needed — readable code wins by default.

---

# Error Handling

Handle errors gracefully with useful messages. Log unexpected failures. Never silently swallow exceptions or catch-and-ignore.

---

# Naming Conventions

Use descriptive names: `getUserProfile()`, `createPurchase()`, `updatePaymentMethod()`.

Avoid vague names like `data`, `temp`, `value`, `object`.

---

# SQL Standards

Use descriptive, clearly named constraints and foreign keys.

Write reversible migrations. Avoid destructive migrations unless requested. Prefer normalized design.

---

# Git

Make focused commits. Do not modify unrelated files. Keep changes small when possible.

When a change requires a new or modified table, generate the migration file for review rather than applying it directly, unless told otherwise.

---

# Testing Standards

Testing is required for all non-trivial features.

**Unit tests** — pure functions/business logic, no Supabase or network dependency, located near services or in `__tests__`.

**Integration tests** — Supabase queries + RLS behavior; must simulate both authenticated and unauthenticated users, and confirm users cannot access other users' data.

**UI/component tests** — critical user flows only, not implementation details.

Always test:
- Authentication flows
- Payment or purchase logic
- Database write operations
- RLS-sensitive queries
- Business-critical services (e.g. creating orders, saving payment methods)

Skip testing by default for:
- Simple UI layout with no logic
- Static screens
- Pure styling changes

Testing philosophy:
- Prefer meaningful tests over coverage for its own sake
- Every test should prevent a real regression risk
- Before implementing: ask "what could break here?" and test that

---

# Scalability Philosophy

Design for practical scalability, not theoretical scale.

- Build for a real production user base (10k–1M range), not hypothetical massive scale
- Avoid premature microservices or distributed systems — prefer modular monolith
- Keep Supabase/Postgres as the primary scaling backbone unless proven insufficient
- Choose the simpler solution today if it can evolve later without a major rewrite

---

# Things to Avoid

Do not:
- Disable RLS
- Use `any` without good reason
- Duplicate business logic
- Mix UI and database code
- Hardcode secrets
- Hardcode fallback/empty UI states without a backing query
- Guess at database schema instead of reading migrations
- Over-engineer simple problems
- Introduce unnecessary dependencies

---

# AI Development Workflow

When implementing a new feature:

1. Understand the existing architecture and locate relevant schema/migrations first.
2. Reuse existing patterns (services, hooks, fetch functions) rather than duplicating logic.
3. Identify what could break (security, RLS, data correctness) and cover it with tests.
4. Explain important architectural decisions and any assumptions made.
5. If context is missing (schema, related file, business rule), say so and ask or search rather than filling the gap with a plausible-looking guess.
6. Keep code production-ready and consistent with project conventions.

If multiple implementations are possible, prefer the simplest one that remains maintainable and secure.

Every change should leave the codebase cleaner than it was before.