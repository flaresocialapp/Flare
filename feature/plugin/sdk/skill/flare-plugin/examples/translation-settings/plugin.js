function documentWithPrefix(document, prefix, targetLanguage) {
  return { ...document, targetLanguage, blocks: document.blocks.map(block => ({
    ...block, tokens: block.tokens.map(token => token.kind === "Translatable"
      ? { ...token, text: prefix + token.text } : token),
  })) };
}
async function prefix() {
  const { values } = await flare.storage.open("options").read(["prefix"]);
  return typeof values.prefix === "string" ? values.prefix : "[demo] ";
}
defineFlarePlugin({ translation: {
  async translateDocumentJson(request) {
    return JSON.stringify(documentWithPrefix(JSON.parse(request.sourceJson), await prefix(), request.targetLanguage));
  },
  async translateBatchDocumentJson(request) {
    const value = await prefix();
    return JSON.stringify({ ...request.sourceDocument, items: request.sourceDocument.items.map(item => ({
      ...item, payload: item.payload && Object.fromEntries(Object.entries(item.payload).map(([field, document]) =>
        [field, document && documentWithPrefix(document, value, request.targetLanguage)])),
    })) });
  },
} });
