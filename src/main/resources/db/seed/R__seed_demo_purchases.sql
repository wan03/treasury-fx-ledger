-- =============================================================================
-- R__seed_demo_purchases — demo seed data for the *running* service.
--
-- A repeatable Flyway migration (re-applied only when its checksum changes), kept
-- in a SEPARATE location (classpath:db/seed) that is wired into the `dev` and
-- `prod` flyway locations but DELIBERATELY NOT `test` — so the deterministic test
-- suite still starts from an empty ledger (see application.yml + data-model.md).
--
-- NB: data-model.md describes R__seed as "dev-only, guarded from prod". We extend
-- it to prod ON PURPOSE here: the prod deployment *is* a public demo, and an empty
-- ledger reads as broken. These are obvious-but-realistic corporate-card rows.
--
-- Idempotent: ON CONFLICT DO NOTHING keeps re-application a no-op and never mutates
-- an existing row (purchases stay append-only — constitution §6). The ids are real
-- UUIDv7 values whose embedded timestamp matches created_at, so they sort by time.
-- Runs as the `migration` role (table owner); USD only (currency CHECK).
-- =============================================================================

INSERT INTO purchases (id, description, transaction_date, amount, currency, created_at) VALUES
  ('019b9dfc-d458-750e-b088-52c157ab639d', 'Adobe Creative Cloud - annual team plan', '2026-01-08',  8399.40, 'USD', '2026-01-08T14:22:31Z'),
  ('019bc0e6-d540-7cfe-91cf-43fba0b4d643', 'United UA931 SFO-LHR - business class',   '2026-01-15',  4218.60, 'USD', '2026-01-15T09:05:12Z'),
  ('019bd7c7-33b8-74ef-9773-de735c8b34d1', 'Marriott London Kensington - 4 nights',   '2026-01-19',  1576.88, 'USD', '2026-01-19T19:41:55Z'),
  ('019c2258-2928-7486-ba74-6cf69890a778', 'Shell fleet fuel - Rhine-Main depot',     '2026-02-03',   743.21, 'USD', '2026-02-03T07:12:09Z'),
  ('019c26b3-22c0-73f4-8a65-d2ceaf7a31c2', 'AWS - January usage, EU-Frankfurt',       '2026-02-04', 12904.77, 'USD', '2026-02-04T03:30:00Z'),
  ('019c51ae-3fa0-760f-9efe-1e7beed8f680', 'DB Schenker - freight invoice INV-88213', '2026-02-12',  9650.00, 'USD', '2026-02-12T11:48:20Z'),
  ('019c5cb3-5ac0-73b0-a261-4c16b1d13f14', 'Client dinner - Jiro, Ginza Tokyo',       '2026-02-14',   412.00, 'USD', '2026-02-14T15:09:44Z'),
  ('019cad8f-ac00-7668-b401-6aabf0c6b178', 'Deutsche Telekom - Q1 roaming & data',     '2026-03-02',   388.45, 'USD', '2026-03-02T08:00:00Z'),
  ('019cddb7-c048-7937-92b7-d551426b70d3', 'WeWork Moorgate - meeting room day pass',  '2026-03-11',   540.00, 'USD', '2026-03-11T16:25:33Z'),
  ('019d9146-fd90-714f-ac1b-c925835e038d', 'Staples - office supplies restock',        '2026-04-15',   247.83, 'USD', '2026-04-15T13:14:02Z')
ON CONFLICT (id) DO NOTHING;
