# ClojureScript Studio

_Interactive ClojureScript playground running on bootstrapped ClojureScript_

## Run locally

### Client
_Takes some time to build initially_
1. `cd playground`
1. `npm i`
1. `npm run dev`

### Server
_Using Cloudflare workers_
1. Update `backend/wrangler.toml` with db and kv ids (generate via wrangler cli)
1. `cd backend`
1. `npm i`
1. `npm start`