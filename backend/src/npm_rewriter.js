import { parse } from "@babel/parser";
import traverse from "@babel/traverse";
import generate from "@babel/generator";
import * as t from "@babel/types";
import template from "@babel/template";
import { createConfigItem, transformFromAstSync } from "@babel/core";
import babelPluginCommonJS from "@babel/plugin-transform-modules-commonjs";
import * as apath from "aurelia-path";

const makeShadowWrapper = template(
	`(function() {
	    var process = { env: { NODE_ENV: %%NODE_ENV_STR%% } };
			shadow$provide[%%NS_STR%%] = function(global, require, module, exports) {
				%%MODULE_BODY%%
			};
			goog.provide(%%NS_STR%%);
			goog.global[%%NS_STR%%] = shadow.js.require(%%NS_STR%%, {});
		})();`)

function getNPMPackageInfo(name, version) {
	return fetch(`https://unpkg.com/${name}@${version}/package.json`)
	.then(r => r.json());
}

let currentDeps = {};
let currentPeerDeps = {};

export async function fetchFile(p, path) {
	const codeText = await fetch(path).then(r => r.text());
	const {code, deps} = rewrite(path, p, codeText);
	const reDeps = await fetchDeps(deps);
	return { code, deps: reDeps };
}

export async function fetchPackage(name, version) {
	const uri = `https://unpkg.com/${name}@${version}`;
	const meta = await fetch(`${uri}?meta`).then(r => r.json());
	const path = `${uri}${meta.path}`;
	const codeText = await fetch(path).then(r => r.text());

	const info = await getNPMPackageInfo(name, version);

	const prevDeps = currentDeps;
	const prevPeerDeps = currentPeerDeps;
	currentDeps = info.dependencies || {};
	currentPeerDeps = info.peerDependencies || {};

	const {code, deps} = rewrite(path, name, codeText);

	const reDeps = await fetchDeps(deps);

	currentDeps = prevDeps;
	currentPeerDeps = prevPeerDeps;

	return { code, deps: reDeps };
}

function fetchDeps(deps) {
	return Promise.all(deps.map(([p, path]) => {
		if (path.startsWith("http")) {
			return fetchFile(p, path);
		} else {
			if (path in currentDeps) {
				return fetchPackage(path, currentDeps[path]);
			}
			if (path in currentPeerDeps) {
				return { peer: currentPeerDeps[path] }
			}
			throw new Error(`can't resolve ${path}`);
		}
	}));
}

export function rewrite(path, module, body) {
	const ast = parse(body);

	const outAST = t.file(t.program([makeShadowWrapper({
		MODULE_BODY: ast.program.body,
		NS_STR: t.stringLiteral(module),
		NODE_ENV_STR: t.stringLiteral("development"),
	})]))

	let deps = new Set();

	traverse(outAST, {
		CallExpression(path) {
			if (path.node.callee.name === "require") {
				if (path.node.arguments.length === 1 && t.isStringLiteral(path.node.arguments[0])) {
					deps.add(path.node.arguments[0].value);
				}
			}
		},
	});

	deps = Array.from(deps).map((p) => {
		if (p.endsWith(".cjs") || p.endsWith(".js")) {
			const parts = path.split("/");
			const uri = parts.slice(0, parts.length - 1).join("/")
			let depPath = apath.join(uri, p);
			return [p, depPath];
		}
		return [p, p];
	}, {});

	const result = transformFromAstSync(outAST, {
		plugins: [createConfigItem(babelPluginCommonJS)],
		filename: path,
	});

	return { code: result.code, deps };
}
