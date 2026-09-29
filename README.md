# Glintly Backend (Ktor)

## Run locally
```
docker compose up -d          # starts Postgres
cp .env.example .env          # fill in real values
export $(grep -v '^#' .env | xargs)
./gradlew run
```

## Deploy
Push this repo to GitHub, then connect it as the source of your Railway service.
Set the variables from `.env.example` in Railway's service Variables tab
(DATABASE_URL/DB_USER/DB_PASS come from the Postgres service Railway already created —
reference them with `${{Postgres.DATABASE_URL}}` etc., or copy the values).

Tables are created automatically on boot — no manual migration step.
