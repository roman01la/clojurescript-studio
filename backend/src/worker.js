import apiRouter, {withCors} from "./router";

export default {
	async fetch(request, env, ctx) {
		const url = new URL(request.url);

		if (url.pathname === "/c") {
			return withCors(env, request, new Response(null, {status: 200}));
		}

		return apiRouter.handle(request, env).then(r => withCors(env, request, r));
	},
};
