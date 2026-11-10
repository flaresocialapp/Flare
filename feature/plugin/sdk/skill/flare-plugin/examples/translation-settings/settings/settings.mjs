import { connectSettings } from "./sdk.mjs";
const status = document.querySelector("#status");
const form = document.querySelector("#options");
const input = document.querySelector("#prefix");
try {
  const sdk = await connectSettings();
  const store = sdk.storage.open("options");
  let snapshot = await store.read();
  input.value = typeof snapshot.values.prefix === "string" ? snapshot.values.prefix : "[demo] ";
  form.addEventListener("submit", async event => {
    event.preventDefault();
    try {
      const result = await store.write({ set: { prefix: input.value } }, { expectedRevision: snapshot.revision });
      snapshot = { values: { prefix: input.value }, revision: result.revision };
      status.textContent = "Saved. New translations will use this prefix.";
    } catch (error) {
      status.textContent = error.code === "storage.conflict" ? "Settings changed elsewhere. Reopen this page before saving." : error.message;
    }
  });
} catch (error) { status.textContent = error.message; }
