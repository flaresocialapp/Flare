defineFlarePlatformExtensionPlugin({
  actions: {
    async verifyLogin(request, context) {
      const response = await flare.account.http.request({
        method: "GET",
        url: "https://m.weibo.cn/api/config",
        redirect: "manual",
      });
      if (response.status === 401 || response.status === 403 || response.status === 302) {
        return { status: "authentication-required" };
      }
      if (response.status !== 200) return { status: "failed", failed: 1 };
      const body = JSON.parse(response.body);
      if (!body.data?.login) return { status: "authentication-required" };
      const store = flare.storage.open("accounts");
      await store.write({ set: { [context.accountId]: { lastVerifiedDate: request.businessDate } } });
      return { status: "completed", succeeded: 1 };
    },
  },
});
