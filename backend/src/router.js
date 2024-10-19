import { Router } from "itty-router";
// import * as npm_rewriter from "./npm_rewriter";

const router = Router();

function notFound() {
	return new Response(null, { status: 404, headers: { "Content-Type": "application/json" } });
}

const ORIGINS = ["http://localhost:3000", "http://localhost:3001"];

export function withCors(env, request, response) {
	if (ORIGINS.includes(request.headers.get("origin"))) {
		response.headers.set("Access-Control-Allow-Origin", request.headers.get("origin"));
	}
	response.headers.set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
	response.headers.set("Access-Control-Allow-Headers", "Content-Type, Baggage, Sentry-Trace");
	response.headers.set("Access-Control-Max-Age", "86400");
	response.headers.set("Vary", "Origin");

	return response;
}

function asJSON(obj) {
	return new Response(JSON.stringify(obj), {headers: {"Content-Type": "application/json"}})
}

function asString(s) {
	return new Response(s)
}

const MAX_FILE_SIZE = 10e3;
const MAX_PATH_LENGTH = 1024;
const MAX_NAME_LENGTH = 128;
const MAX_TOKEN_LENGTH = 50;

function areFilesValid(files) {
	return Object.entries(files).every(([path, f]) => {
		return (f.type === "folder" || (f.type === "file" && f.content.length <= MAX_FILE_SIZE)) &&
			path.length <= MAX_PATH_LENGTH && f.path.length <= MAX_PATH_LENGTH && path === f.path &&
			f.name.length <= MAX_NAME_LENGTH;
	})
}

router.options("*", (request, env) => new Response(null));

router.get("/count", async ({params, headers}, env) => {
	const {results} = await env.DB.prepare("SELECT COUNT(*) as count FROM Projects;").all();
	return asJSON(results[0]);
});


router.get("/p/:id", async ({ params }, env) => {
	const object = await env.UIXP.get(params.id, { type: "json" });
	if (object) {
		const { token, ...obj } = object;
		return asJSON(obj);
	} else {
		return notFound();
	}
});

function selectFiles(files) {
	if (files) {
		return Object.fromEntries(Object.entries(files)
			.map(([p, {id, content, name, path, type, created_at, updated_at}]) => [p, type === "file" ? {
				id,
				content,
				name,
				path,
				type,
				created_at,
				updated_at
			} : {
				name,
				path,
				type
			}]));
	}
}

router.post("/p/:id", async (request, env) => {
	const { id } = request.params;
	const { files, token } = await request.json();
	if (!token || token.length > MAX_TOKEN_LENGTH || !files || !areFilesValid(files)) {
		return notFound();
	}
	if (await env.UIXP.get(id)) {
		return notFound();
	}

	const dbFiles = selectFiles(files);
	const data = {files: dbFiles, created_at: Date.now()};
	await env.UIXP.put(id, JSON.stringify({...data, token}));
	await env.DB.prepare("INSERT INTO Projects (id, name, token, created_at, updated_at) VALUES (?1, ?2, ?3, ?4, ?5);")
	.bind(id, "", token, data.created_at, data.created_at).run();
	return asJSON(data);
});

router.put("/p/:id", async (request, env) => {
	const { id } = request.params;
	const { files, token, name } = await request.json();
	if (name && name.length > MAX_NAME_LENGTH) {
		return notFound();
	}
	if (files && !areFilesValid(files)) {
		return notFound();
	}
	if (!token || token.length > MAX_TOKEN_LENGTH) {
		return notFound();
	} else {
		const object = await env.UIXP.get(id, { type: "json" });

		if (object && token === object.token) {
			const dbFiles = selectFiles(files);
			const data = { ...object, updated_at: Date.now() };
			if (name) {
				data.name = name;
			}
			if (dbFiles) {
				data.files = dbFiles;
			}
			await env.UIXP.put(id, JSON.stringify(data));
			await env.DB.prepare("UPDATE Projects SET name = ?1, updated_at = ?2 WHERE id = ?3 AND token = ?4;").bind(data.name || "", data.updated_at, id, object.token).run();
			const { token, ...obj } = data;
			return asJSON(obj);
		} else {
			return notFound();
		}
	}
});

router.delete("/p/:id", async (request, env) => {
	const { id } = request.params;
	const { token } = await request.json();
	if (!token) {
		return notFound();
	} else {
		const object = await env.UIXP.get(id, { type: "json" });

		if (object && token === object.token) {
			await env.UIXP.delete(id);
			// await env.DB.prepare("DELETE FROM Projects WHERE id = ?1 AND token = ?2;").bind(id, token).run();
			return asJSON({});
		} else {
			return notFound();
		}
	}
});

// router.get("/npm", async (request, env) => {
// 	const module = request.query.name;
// 	const version = request.query.v;
//
// 	if (!module || !version) {
// 		return notFound();
// 	}
//
// 	const pkg = await npm_rewriter.fetchPackage(module, version);
//
// 	return asJSON(pkg);
// });

router.all("*", notFound);

export default router;
