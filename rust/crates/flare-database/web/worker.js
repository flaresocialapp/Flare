import init, { __flare_database_owner } from './flare_database.js';

const name = new URL(import.meta.url).searchParams.get('name');
const kind = new URL(import.meta.url).searchParams.get('kind');
if (!name || !/^[A-Za-z0-9_-]{1,100}$/.test(name)) {
    throw new Error('Invalid database name');
}
if (!['app', 'agent', 'cache'].includes(kind)) throw new Error('Invalid database kind');
if (!navigator.locks) {
    throw new Error('Web Locks are required for the database owner');
}

// Waiting candidates never initialize SQLite or acquire any OPFS handles.
await navigator.locks.request(`flare-rust-app:${name}`, async () => {
    await init();
    await __flare_database_owner(name, kind);
});
