import assert from 'node:assert/strict';
import fs from 'node:fs';

const shell = fs.readFileSync('components/AppShell.tsx', 'utf8');
const css = fs.readFileSync('app/globals.css', 'utf8');
const nav = shell.slice(shell.indexOf('<nav'), shell.indexOf('</nav>'));
assert.match(shell, /className="nav-toggle"/);
assert.match(nav, /className="nav-account"/);
assert.match(nav, /href=\{user\?'\/account':'\/login'\}/);
assert.match(css, /@media \(max-width:860px\)/);
assert.match(css, /\.nav-toggle\{display:none/);
assert.match(css, /\.nav-toggle\{display:inline-flex/);
assert.match(css, /\.topbar nav\{display:none/);
assert.doesNotMatch(css.slice(0, css.indexOf('@media (max-width:860px)')), /\.topbar nav\{display:none/);

console.log('shell-nav ok');
