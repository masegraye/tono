// Development only: reload this tab when a new build of the web app lands.
//
// `task web:watch` rebuilds the app when sources change and then writes a fresh
// dev-build-id.txt next to the served files. Outside local development the file does
// not exist and this script does nothing.
(() => {
  if (!["localhost", "127.0.0.1", "[::1]"].includes(location.hostname)) return;

  let loadedBuild = null; // build id seen when this page loaded ("" if there was none)

  setInterval(async () => {
    let latest = "";
    try {
      const response = await fetch("dev-build-id.txt", { cache: "no-store" });
      // The file is briefly missing while a build rewrites the output directory.
      if (!response.ok && loadedBuild !== null) return;
      if (response.ok) latest = (await response.text()).trim();
    } catch (_) {
      return; // server is restarting
    }
    if (loadedBuild === null) loadedBuild = latest;
    else if (latest !== loadedBuild) location.reload();
  }, 1000);
})();
