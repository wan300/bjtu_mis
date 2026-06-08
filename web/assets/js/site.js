(function () {
  const repoApi = "https://api.github.com/repos/wan300/bjtu_mis_Android/releases/latest";
  const fallbackApk = "https://github.com/wan300/bjtu_mis_Android/releases/download/v1.2.1/app-release.apk";
  const fallbackRelease = "https://github.com/wan300/bjtu_mis_Android/releases/tag/v1.2.1";
  const fallbackTag = "v1.2.1";
  const fallbackSizeText = "--";
  const releaseRefreshInterval = 5 * 60 * 1000;
  const imageAssetQuery = "?v=2026060603";

  const moduleData = window.BJTU_MODULES || [];
  const isModulePage = document.body.dataset.page === "module";
  const rootPrefix = isModulePage ? "../" : "";
  const assetPrefix = `${rootPrefix}assets/`;

  function escapeHtml(value) {
    return String(value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  function iconPath(icon) {
    return `${assetPrefix}icons/${icon}${imageAssetQuery}`;
  }

  function moduleLink(slug) {
    return isModulePage ? `${slug}.html` : `modules/${slug}.html`;
  }

  function formatBytes(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) return "";
    const units = ["B", "KB", "MB", "GB"];
    let value = bytes;
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) {
      value /= 1024;
      unit += 1;
    }
    return `${value.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`;
  }

  function categoryGroups() {
    const groups = new Map();
    moduleData.forEach((item) => {
      if (!groups.has(item.category)) groups.set(item.category, []);
      groups.get(item.category).push(item);
    });
    return Array.from(groups.entries());
  }

  function initNav() {
    const toggle = document.querySelector("[data-nav-toggle]");
    if (!toggle) return;
    toggle.addEventListener("click", () => {
      const open = document.body.classList.toggle("nav-open");
      toggle.setAttribute("aria-expanded", String(open));
    });
  }

  function renderHomeModules() {
    const container = document.querySelector("[data-module-grid]");
    if (!container) return;

    container.innerHTML = moduleData
      .map(
        (item) => `
          <a class="module-card reveal" href="${moduleLink(item.slug)}" style="--accent:${item.accent}">
            <span class="module-card__marker">${escapeHtml(item.category)}</span>
            <img src="${iconPath(item.icon)}" alt="" loading="lazy" width="72" height="72">
            <span class="module-card__title">${escapeHtml(item.title)}</span>
            <span class="module-card__subtitle">${escapeHtml(item.subtitle)}</span>
          </a>
        `
      )
      .join("");
  }

  function renderCategoryRails() {
    const container = document.querySelector("[data-category-rails]");
    if (!container) return;
    container.innerHTML = categoryGroups()
      .map(
        ([category, items]) => `
          <section class="module-band reveal" aria-labelledby="category-${escapeHtml(category)}">
            <div>
              <p class="eyebrow">Module line</p>
              <h3 id="category-${escapeHtml(category)}">${escapeHtml(category)}</h3>
            </div>
            <div class="module-band__links">
              ${items
                .map(
                  (item) => `
                    <a href="${moduleLink(item.slug)}" style="--accent:${item.accent}">
                      <img src="${iconPath(item.icon)}" alt="" loading="lazy" width="40" height="40">
                      <span>${escapeHtml(item.title)}</span>
                    </a>
                  `
                )
                .join("")}
            </div>
          </section>
        `
      )
      .join("");
  }

  function renderModulePage() {
    const slug = document.body.dataset.module;
    const root = document.querySelector("[data-module-root]");
    if (!slug || !root) return;

    const index = moduleData.findIndex((item) => item.slug === slug);
    const item = moduleData[index];
    if (!item) {
      root.innerHTML = `
        <section class="module-page module-page--missing">
          <p class="eyebrow">Not found</p>
          <h1>模块不存在</h1>
          <p>请返回主页重新选择模块。</p>
          <a class="btn btn--primary" href="../index.html">返回主页</a>
        </section>
      `;
      return;
    }

    const previous = moduleData[(index - 1 + moduleData.length) % moduleData.length];
    const next = moduleData[(index + 1) % moduleData.length];
    document.title = `${item.title} - BJTU MIS Android`;

    root.innerHTML = `
      <section class="subhero" style="--accent:${item.accent}">
        <div class="subhero__copy reveal">
          <a class="back-link" href="../index.html">返回主页</a>
          <p class="eyebrow">${escapeHtml(item.category)}</p>
          <h1>${escapeHtml(item.title)}</h1>
          <p>${escapeHtml(item.summary)}</p>
          <div class="subhero__actions">
            <a class="btn btn--primary" href="../index.html#download">下载应用</a>
            <a class="btn btn--ghost" href="../index.html#modules">浏览全部模块</a>
          </div>
        </div>
        <div class="subhero__visual reveal">
          <span class="subhero__sign">${escapeHtml(item.key)}</span>
          <img class="subhero__icon" src="${iconPath(item.icon)}" alt="" width="180" height="180">
        </div>
      </section>

      <section class="content-section content-section--tight">
        <div class="section-heading reveal">
          <p class="eyebrow">Use case</p>
          <h2>适用场景</h2>
          <p>${escapeHtml(item.scenario)}</p>
        </div>
        <div class="ability-grid">
          ${item.abilities
            .map(
              (ability, abilityIndex) => `
                <article class="ability-card reveal" style="--accent:${item.accent}">
                  <span>${String(abilityIndex + 1).padStart(2, "0")}</span>
                  <p>${escapeHtml(ability)}</p>
                </article>
              `
            )
            .join("")}
        </div>
      </section>

      <section class="module-nav reveal">
        <a href="${moduleLink(previous.slug)}" style="--accent:${previous.accent}">
          <span>上一站</span>
          <strong>${escapeHtml(previous.title)}</strong>
        </a>
        <a href="${moduleLink(next.slug)}" style="--accent:${next.accent}">
          <span>下一站</span>
          <strong>${escapeHtml(next.title)}</strong>
        </a>
      </section>
    `;
  }

  function initDownload() {
    const buttons = Array.from(document.querySelectorAll("[data-download-button]"));
    const releaseLinks = Array.from(document.querySelectorAll("[data-release-link]"));
    const versions = Array.from(document.querySelectorAll("[data-release-version]"));
    const sizes = Array.from(document.querySelectorAll("[data-release-size]"));
    let lastReleaseFetch = 0;
    let releaseFetchInFlight = null;

    buttons.forEach((button) => {
      button.href = fallbackApk;
    });
    releaseLinks.forEach((link) => {
      link.href = fallbackRelease;
    });
    versions.forEach((node) => {
      node.textContent = fallbackTag;
    });
    sizes.forEach((node) => {
      node.textContent = fallbackSizeText;
    });

    if (!buttons.length && !releaseLinks.length) return;

    function findApkAsset(assets) {
      if (!Array.isArray(assets)) return null;
      return (
        assets.find((asset) => asset.name === "app-release.apk") ||
        assets.find((asset) => /\.apk$/i.test(asset.name))
      );
    }

    function applyRelease(release) {
      const apk = findApkAsset(release.assets);
      const apkUrl = apk && apk.browser_download_url ? apk.browser_download_url : fallbackApk;
      const releaseUrl = release.html_url || fallbackRelease;
      const tag = release.tag_name || fallbackTag;
      const sizeText = apk && apk.size ? formatBytes(apk.size) : fallbackSizeText;

      buttons.forEach((button) => {
        button.href = apkUrl;
        button.dataset.version = tag;
      });
      releaseLinks.forEach((link) => {
        link.href = releaseUrl;
      });
      versions.forEach((node) => {
        node.textContent = tag;
      });
      sizes.forEach((node) => {
        node.textContent = sizeText;
      });
      document.body.classList.remove("release-fallback");
    }

    function syncRelease(force) {
      const now = Date.now();
      if (!force && now - lastReleaseFetch < releaseRefreshInterval) return releaseFetchInFlight;
      if (releaseFetchInFlight) return releaseFetchInFlight;

      lastReleaseFetch = now;
      releaseFetchInFlight = fetch(repoApi, {
        cache: "no-store",
        headers: { Accept: "application/vnd.github+json" }
      })
        .then((response) => {
          if (!response.ok) throw new Error(`GitHub API ${response.status}`);
          return response.json();
        })
        .then(applyRelease)
        .catch(() => {
          document.body.classList.add("release-fallback");
        })
        .finally(() => {
          releaseFetchInFlight = null;
        });

      return releaseFetchInFlight;
    }

    syncRelease(true);

    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState === "visible") syncRelease(false);
    });
    window.addEventListener("focus", () => syncRelease(false));
    window.addEventListener("pageshow", () => syncRelease(false));
  }

  function initReveal() {
    const items = Array.from(document.querySelectorAll(".reveal"));
    if (!items.length) return;
    if (!("IntersectionObserver" in window) || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      items.forEach((item) => item.classList.add("is-visible"));
      return;
    }

    const observer = new IntersectionObserver(
      (entries) => {
        entries.forEach((entry) => {
          if (entry.isIntersecting) {
            entry.target.classList.add("is-visible");
            observer.unobserve(entry.target);
          }
        });
      },
      { threshold: 0.16 }
    );

    items.forEach((item) => observer.observe(item));
  }

  function initYear() {
    document.querySelectorAll("[data-current-year]").forEach((node) => {
      node.textContent = String(new Date().getFullYear());
    });
  }

  document.addEventListener("DOMContentLoaded", () => {
    initNav();
    renderHomeModules();
    renderCategoryRails();
    renderModulePage();
    initDownload();
    initReveal();
    initYear();
  });
})();
