const paths = {
  session: "/web/blog/session",
  search: "/form-api/blog-component/blog/search-posts",
  get: "/form-api/blog-component/blog/get-post",
  searchMy: "/form-api/blog-component/blog/search-my-posts",
  getMy: "/form-api/blog-component/blog/get-my-post",
  save: "/form-api/blog-component/blog/save-editor-post",
  importTree: "/form-api/blog-component/blog/import-post-tree",
  images: "/form-api/blog-component/blog/list-image-blobs",
  tags: "/form-api/blog-component/blog/list-tags",
  notificationSummary: "/form-api/textus-user-notification/notification/get-notification-summary",
  jobs: "/rest/v1/job_control/job/await_job_result"
};

const state = {
  session: null,
  posts: [],
  tags: [],
  currentPost: null,
  activeTag: new URLSearchParams(location.search).get("tag") || "",
  page: document.body.dataset.page || "reader"
};

const els = {
  login: document.querySelector("[data-login-link]"),
  signup: document.querySelector("[data-signup-link]"),
  myPosts: document.querySelectorAll("[data-my-posts-link]"),
  notificationIndicators: document.querySelectorAll("[data-notification-indicator]"),
  notificationBadges: document.querySelectorAll("[data-notification-badge]"),
  logout: document.querySelector("[data-logout-form]"),
  sessionName: document.querySelector("[data-session-name]"),
  searchForm: document.querySelector("[data-search-form]"),
  listSection: document.querySelector("[data-list-section]"),
  detailSection: document.querySelector("[data-detail-section]"),
  postList: document.querySelector("[data-post-list]"),
  myPostList: document.querySelector("[data-my-post-list]"),
  articleTitle: document.querySelector("[data-article-title]"),
  articleBody: document.querySelector("[data-article-body]"),
  backToList: document.querySelector("[data-back-to-list]"),
  editorForm: document.querySelector("[data-editor-form]"),
  editorHeading: document.querySelector("[data-editor-heading]"),
  editorId: document.querySelector("[data-editor-id]"),
  editorSlug: document.querySelector("[data-editor-slug]"),
  editorTitle: document.querySelector("[data-editor-title]"),
  editorMarkup: document.querySelector("[data-editor-markup]"),
  editorContent: document.querySelector("[data-editor-content]"),
  editorDescription: document.querySelector("[data-editor-description]"),
  editorTags: document.querySelector("[data-editor-tags]"),
  tagInputs: document.querySelectorAll("[data-tag-input]"),
  tagSuggestions: document.querySelectorAll("[data-tag-suggestions]"),
  editorPublish: document.querySelector("[data-editor-publish]"),
  tagNav: document.querySelector("[data-tag-nav]"),
  tagFilter: document.querySelector("[data-tag-filter]"),
  tagFilterInput: document.querySelector("[data-tag-filter-input]"),
  tagClear: document.querySelector("[data-tag-clear]"),
  uploadDialog: document.querySelector("[data-upload-dialog]"),
  uploadForm: document.querySelector("[data-upload-form]"),
  imageDialog: document.querySelector("[data-image-dialog]"),
  imageGrid: document.querySelector("[data-image-grid]"),
  toast: document.querySelector("[data-toast]")
};

document.addEventListener("DOMContentLoaded", boot);

async function boot() {
  bindCommonEvents();
  await loadSession();
  initializeEditorMarkupControls();
  if (state.page === "my") {
    if (!requireAuth()) return;
    await loadTags();
    bindDashboardEvents();
    const params = new URLSearchParams(location.search);
    await loadMyPosts(params.get("text") || "", params.get("tag") || "");
  } else if (state.page === "edit") {
    if (!requireAuth()) return;
    await loadTags();
    bindEditorEvents();
    await loadEditorPost();
  } else if (state.page === "reader") {
    await loadTags();
    bindReaderEvents();
    const params = new URLSearchParams(location.search);
    await loadPublicPosts(params.get("text") || "", state.activeTag);
    const postId = params.get("post") || location.hash.replace(/^#post=/, "");
    if (postId) {
      await openPublicPost(postId);
    }
  }
}

function bindCommonEvents() {
  for (const form of document.querySelectorAll("form")) {
    if (form.querySelector("[data-tag-input]")) {
      form.addEventListener("submit", () => normalizeTagInputs(form));
    }
    if (form.querySelector("[data-editor-markup-value]")) {
      form.addEventListener("submit", () => syncEditorMarkupValue(form));
    }
  }
  if (els.logout) {
    els.logout.addEventListener("submit", async event => {
      event.preventDefault();
      try {
        await fetch(els.logout.action, { method: "POST", credentials: "same-origin" });
      } finally {
        location.href = "/web/blog";
      }
    });
  }
}

function bindReaderEvents() {
  els.searchForm?.addEventListener("submit", async event => {
    event.preventDefault();
    const form = new FormData(els.searchForm);
    await loadPublicPosts(form.get("text") || "", form.get("tag") || "");
  });
}

function bindDashboardEvents() {
  els.searchForm?.addEventListener("submit", async event => {
    event.preventDefault();
    const form = new FormData(els.searchForm);
    await loadMyPosts(form.get("text") || "", form.get("tag") || "");
  });
  document.querySelector("[data-open-upload-dialog]")?.addEventListener("click", () => {
    showModalElement(els.uploadDialog);
  });
  els.uploadForm?.addEventListener("submit", importPostTree);
}

function bindEditorEvents() {
  document.querySelector("[data-open-image-picker]")?.addEventListener("click", openImagePicker);
}

async function loadSession() {
  try {
    const headers = {};
    const sessionId = textusSessionCookie();
    if (sessionId) headers["x-textus-session"] = sessionId;
    const response = await fetch(paths.session, { credentials: "same-origin", headers });
    state.session = response.ok ? await response.json() : { authenticated: false };
  } catch {
    state.session = { authenticated: false };
  }
  const authenticated = Boolean(state.session?.authenticated);
  if (els.login) els.login.hidden = authenticated;
  if (els.signup) els.signup.hidden = authenticated;
  for (const link of els.myPosts || []) link.hidden = !authenticated;
  if (els.logout) els.logout.hidden = !authenticated;
  if (els.sessionName) els.sessionName.textContent = authenticated ? displayUser() : "";
  if (authenticated && els.notificationIndicators.length > 0) {
    loadNotificationSummary();
  } else {
    renderNotificationBadge(0, false);
  }
}

function textusSessionCookie() {
  const cookies = document.cookie
    .split(";")
    .map(part => part.trim())
    .filter(Boolean)
    .map(part => {
      const index = part.indexOf("=");
      return index >= 0 ? [part.slice(0, index), part.slice(index + 1)] : [part, ""];
    });
  const names = preferredSessionCookieNames();
  for (const name of names) {
    const match = cookies.find(([key]) => key === name);
    if (match && match[1]) return decodeURIComponent(match[1]);
  }
  const fallback = cookies.find(([key, value]) => key.startsWith("textus-session-") && value);
  return fallback ? decodeURIComponent(fallback[1]) : "";
}

function preferredSessionCookieNames() {
  const names = ["textus-session-blog"];
  const match = location.pathname.match(/^\/web\/([^/?#]+)/);
  if (match && match[1]) {
    names.push("textus-session-" + match[1].toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, ""));
  }
  return Array.from(new Set(names.filter(Boolean)));
}

async function loadNotificationSummary() {
  try {
    const result = await postForm(paths.notificationSummary, new FormData(), {
      debugKind: "background",
      debugLabel: "Notification badge summary",
      debugOptional: true,
      debugDisplay: "always",
      timeoutMs: 3000
    });
    const count = Number(result.unconfirmedCount || 0);
    renderNotificationBadge(Number.isFinite(count) ? count : 0, true);
  } catch {
    renderNotificationBadge(0, false);
  }
}

function renderNotificationBadge(count, authenticated) {
  for (const indicator of els.notificationIndicators || []) {
    indicator.hidden = !authenticated;
  }
  for (const badge of els.notificationBadges || []) {
    const value = Math.max(0, count || 0);
    badge.textContent = String(value);
    badge.hidden = !authenticated || value === 0;
  }
}

async function loadTags() {
  try {
    const result = await postForm(paths.tags, new FormData(), {
      debugKind: "background",
      debugLabel: "Blog tag suggestions",
      debugOptional: true,
      debugDisplay: "always",
      timeoutMs: 3000
    });
    state.tags = (result.data || result.body || [])
      .filter(tag => tagPath(tag))
      .sort((a, b) => tagPath(a).localeCompare(tagPath(b)));
  } catch {
    state.tags = [];
  }
  renderTagNavigation();
  renderTagSuggestions();
}

function requireAuth() {
  if (state.session?.authenticated) return true;
  const returnTo = encodeURIComponent(`${location.pathname}${location.search}`);
  location.href = `/web/textus-user-account/signin?returnTo=${returnTo}`;
  return false;
}

async function loadPublicPosts(text, tag = "") {
  const form = new FormData();
  if (text) form.append("text", text);
  if (tag) {
    form.append("tag", tag);
    form.append("includeDescendants", "true");
  }
  form.append("limit", "50");
  const result = await postForm(paths.search, form, {
    debugKind: "page-render",
    debugLabel: "Public post list",
    debugDisplay: "always"
  });
  state.posts = result.data || [];
  state.activeTag = tag || "";
  state.currentPost = null;
  showPublicList();
  renderActiveTagFilter();
  renderPublicPostList();
}

async function loadMyPosts(text, tag = "") {
  const form = new FormData();
  if (text) form.append("text", text);
  if (tag) {
    form.append("tag", tag);
    form.append("includeDescendants", "true");
  }
  form.append("limit", "100");
  const result = await postForm(paths.searchMy, form, {
    debugKind: "page-render",
    debugLabel: "My posts list",
    debugDisplay: "always"
  });
  state.posts = result.data || [];
  renderMyPostList();
}

function renderPublicPostList() {
  if (!els.postList) return;
  els.postList.innerHTML = "";
  if (state.posts.length === 0) {
    els.postList.innerHTML = `<p class="col-12 post-row-meta">No posts.</p>`;
    return;
  }
  for (const post of state.posts) {
    const ref = publicPostRef(post);
    const column = document.createElement("div");
    column.className = "col-12";
    const button = document.createElement("div");
    button.className = "post-row list-group-item list-group-item-action card h-100 d-flex flex-row align-items-start gap-3 p-3";
    button.role = "button";
    button.tabIndex = 0;
    if (state.currentPost && publicPostRef(state.currentPost) === ref) button.classList.add("is-active");
    button.addEventListener("click", () => openPublicPost(ref));
    button.addEventListener("keydown", event => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        openPublicPost(ref);
      }
    });
    const body = rowText(post.title || post.slug || readId(post), post.slug || "");
    body.append(tagChipList(postTags(post), { interactive: true, compact: true }));
    button.append(postThumb(post), body);
    column.append(button);
    els.postList.append(column);
  }
}

function renderMyPostList() {
  if (!els.myPostList) return;
  els.myPostList.innerHTML = "";
  if (state.posts.length === 0) {
    els.myPostList.innerHTML = `<p class="col-12 post-row-meta">No posts.</p>`;
    return;
  }
  for (const post of state.posts) {
    const column = document.createElement("div");
    column.className = "col-12";
    const row = document.createElement("div");
    row.className = "post-row dashboard-row card d-flex flex-row align-items-start gap-3 p-3";
    const body = rowText(post.title || post.slug || readId(post), `${post.slug || ""} ${statusText(post)}`);
    body.append(tagChipList(postTags(post), { interactive: true, compact: true }));
    const actions = document.createElement("div");
    actions.className = "post-actions";
    actions.append(linkButton("Edit", `/web/blog/update?id=${encodeURIComponent(editPostRef(post))}`));
    if (isPublicPost(post)) {
      actions.append(linkButton("View public", `/web/blog/publicblogs?post=${encodeURIComponent(publicPostRef(post))}`));
    }
    body.append(actions);
    row.append(postThumb(post), body);
    column.append(row);
    els.myPostList.append(column);
  }
}

async function openPublicPost(id) {
  if (!id) return;
  const form = new FormData();
  form.append("id", id);
  const post = await postForm(paths.get, form, {
    debugKind: "page-render",
    debugLabel: "Public post detail",
    debugDisplay: "always"
  });
  state.currentPost = post;
  showPublicDetail();
  window.scrollTo(0, 0);
  if (els.backToList) {
    els.backToList.href = publicListHref();
    els.backToList.hidden = false;
  }
  if (els.articleTitle) els.articleTitle.textContent = post.title || post.slug || "";
  if (els.articleBody) {
    els.articleBody.innerHTML = "";
    els.articleBody.append(tagChipList(postTags(post), { interactive: true }));
    const body = document.createElement("div");
    body.innerHTML = post.content || "";
    els.articleBody.append(body);
  }
  renderPublicPostList();
  const tagPart = state.activeTag ? `&tag=${encodeURIComponent(state.activeTag)}` : "";
  history.replaceState(null, "", `/web/blog/publicblogs?post=${encodeURIComponent(publicPostRef(post) || id)}${tagPart}`);
}

function showPublicList() {
  els.listSection?.classList.remove("d-none");
  els.detailSection?.classList.add("d-none");
  if (els.backToList) els.backToList.hidden = true;
}

function showPublicDetail() {
  els.listSection?.classList.add("d-none");
  els.detailSection?.classList.remove("d-none");
  if (els.backToList) els.backToList.hidden = false;
}

function publicListHref() {
  return state.activeTag
    ? `/web/blog/publicblogs?tag=${encodeURIComponent(state.activeTag)}`
    : "/web/blog/publicblogs";
}

async function loadEditorPost() {
  const id = new URLSearchParams(location.search).get("id");
  if (!id) {
    setEditor(null);
    return;
  }
  const form = new FormData();
  form.append("id", id);
  const post = await postForm(paths.getMy, form, {
    debugKind: "page-render",
    debugLabel: "Editor post load",
    debugDisplay: "always"
  });
  state.currentPost = post;
  setEditor(post);
}

function setEditor(post) {
  if (els.editorHeading) els.editorHeading.textContent = post ? "Edit post" : "New post";
  if (els.editorId) els.editorId.value = post ? readId(post) : "";
  if (els.editorSlug) els.editorSlug.value = post?.slug || "";
  if (els.editorTitle) els.editorTitle.value = post?.title || "";
  if (els.editorMarkup) els.editorMarkup.value = contentMarkup(post);
  if (els.editorContent) els.editorContent.value = post ? contentSource(post) : "";
  if (els.editorDescription) els.editorDescription.value = post?.description || "";
  if (els.editorTags) els.editorTags.value = post ? postTags(post).map(tagPath).join("\n") : "";
  if (els.editorPublish) els.editorPublish.checked = post?.postStatus === "published" || post?.post_status === "published";
  renderTagSuggestions();
}

async function importPostTree(event) {
  event.preventDefault();
  const form = new FormData(els.uploadForm);
  const publish = Boolean(form.get("publish"));
  if (!publish) form.delete("publish");
  try {
    await postForm(paths.importTree, form);
    notice("Imported.");
    els.uploadForm.reset();
    hideModalElement(els.uploadDialog);
    await loadMyPosts("");
  } catch (error) {
    notice(error.message, true);
  }
}

async function openImagePicker() {
  try {
    const form = new FormData();
    form.append("limit", "100");
    const result = await postForm(paths.images, form);
    renderImages(result.data || []);
    showModalElement(els.imageDialog);
  } catch (error) {
    notice(error.message, true);
  }
}

function renderImages(images) {
  if (!els.imageGrid) return;
  els.imageGrid.innerHTML = "";
  if (images.length === 0) {
    els.imageGrid.innerHTML = `<p class="col-12 post-row-meta">No images.</p>`;
    return;
  }
  for (const image of images) {
    const column = document.createElement("div");
    column.className = "col-6 col-md-4 col-lg-3";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "image-card card h-100 p-2 text-start";
    button.innerHTML = `<img alt=""><strong></strong><span class="image-id"></span>`;
    button.querySelector("img").src = image.url;
    button.querySelector("strong").textContent = image.filename || readId(image);
    button.querySelector(".image-id").textContent = readId(image);
    button.addEventListener("click", () => {
      insertAtCursor(els.editorContent, imageReferenceSnippet(readId(image)));
      hideModalElement(els.imageDialog);
    });
    column.append(button);
    els.imageGrid.append(column);
  }
}

async function postForm(url, form, options = {}) {
  const headers = {};
  if (state.session?.sessionId) headers["x-textus-session"] = state.session.sessionId;
  if (options.debugKind) headers["x-textus-debug-request-kind"] = options.debugKind;
  if (options.debugLabel) headers["x-textus-debug-label"] = options.debugLabel;
  if (options.debugOptional !== undefined) headers["x-textus-debug-optional"] = String(Boolean(options.debugOptional));
  if (options.debugDisplay) headers["x-textus-debug-display"] = options.debugDisplay;
  const controller = options.timeoutMs ? new AbortController() : null;
  const timeoutId = controller
    ? setTimeout(() => controller.abort(), options.timeoutMs)
    : null;
  let response = await fetch(url, {
    method: "POST",
    credentials: "same-origin",
    headers,
    body: form,
    signal: controller ? controller.signal : undefined
  });
  let text;
  try {
    text = await response.text();
    if (response.ok && isJobId(text)) {
      const awaited = await awaitJobResult(text.trim());
      response = awaited.response;
      text = awaited.text;
    }
  } finally {
    if (timeoutId) clearTimeout(timeoutId);
  }
  const data = parsePayload(text, response.statusText);
  if (!response.ok || data.status === "failure") {
    throw new Error(errorMessage(data, response.statusText));
  }
  return data.result || data;
}

function isJobId(value) {
  return /^cncf-job-job-/.test((value || "").trim());
}

async function awaitJobResult(jobId) {
  const body = new URLSearchParams();
  body.set("id", jobId);
  const headers = { "Content-Type": "application/x-www-form-urlencoded" };
  if (state.session?.sessionId) headers["x-textus-session"] = state.session.sessionId;
  const response = await fetch(paths.jobs, {
    method: "POST",
    credentials: "same-origin",
    headers,
    body
  });
  const text = await response.text();
  return { response, text };
}

function parsePayload(text, fallback) {
  if (!text) return {};
  try {
    return JSON.parse(text);
  } catch (_) {
    return { status: "failure", message: text || fallback };
  }
}

function errorMessage(data, fallback) {
  if (data.message) return data.message;
  if (typeof data.error === "string") return data.error;
  if (data.error?.message) return data.error.message;
  if (data.error?.code) return data.error.code;
  return fallback;
}

function insertAtCursor(textarea, text) {
  const start = textarea.selectionStart || 0;
  const end = textarea.selectionEnd || start;
  textarea.value = `${textarea.value.slice(0, start)}${text}${textarea.value.slice(end)}`;
  textarea.focus();
  textarea.selectionStart = textarea.selectionEnd = start + text.length;
}

function initializeEditorMarkupControls() {
  for (const select of document.querySelectorAll("[data-editor-markup]")) {
    const current = select.dataset.currentContentMarkup || select.getAttribute("data-current-content-markup");
    if (current) select.value = normalizeContentMarkup(current);
    select.addEventListener("change", () => {
      const form = select.form;
      if (form) syncEditorMarkupValue(form);
    });
    const form = select.form;
    if (form) syncEditorMarkupValue(form);
  }
}

function syncEditorMarkupValue(form) {
  const select = form.querySelector("[data-editor-markup]");
  const target = form.querySelector("[data-editor-markup-value]");
  if (select && target) target.value = normalizeContentMarkup(select.value);
}

function contentSource(post) {
  return post?.contentSource || post?.content_source || post?.rawContent || post?.raw_content || post?.content || "";
}

function contentMarkup(post) {
  return normalizeContentMarkup(post?.contentMarkup || post?.content_markup || post?.markup || "html-fragment");
}

function normalizeContentMarkup(value) {
  const text = String(value || "").trim().toLowerCase();
  if (text === "markdown" || text === "gfm" || text === "markdown-gfm") return "markdown-gfm";
  if (text === "smart-dox" || text === "smartdox") return "smartdox";
  return "html-fragment";
}

function imageReferenceSnippet(id) {
  const src = `/web/blob/content/${id}`;
  switch (normalizeContentMarkup(els.editorMarkup?.value)) {
    case "markdown-gfm":
      return `![](${src})`;
    case "smartdox":
      return `[[${src}]]`;
    default:
      return `<img src="${src}" alt="">`;
  }
}

function postThumb(post) {
  const thumb = document.createElement("img");
  thumb.className = "post-thumb";
  thumb.alt = "";
  thumb.src = post.representativeBlobUrl || "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='72' height='72'%3E%3Crect width='72' height='72' fill='%23edf0f3'/%3E%3C/svg%3E";
  return thumb;
}

function rowText(title, meta) {
  const text = document.createElement("div");
  text.className = "post-row-body flex-grow-1 min-w-0";
  text.innerHTML = `<div class="post-row-title card-title mb-1"></div><div class="post-row-meta text-secondary small"></div>`;
  text.querySelector(".post-row-title").textContent = title;
  text.querySelector(".post-row-meta").textContent = meta;
  return text;
}

function linkButton(label, href) {
  const link = document.createElement("a");
  link.className = "btn btn-sm btn-outline-primary";
  link.href = href;
  link.textContent = label;
  return link;
}

function renderTagNavigation() {
  if (!els.tagNav) return;
  els.tagNav.innerHTML = "";
  const panel = els.tagNav.closest("[data-tag-nav-panel]");
  if (panel) panel.hidden = state.tags.length === 0;
  for (const tag of state.tags.slice(0, 40)) {
    const path = tagPath(tag);
    if (!path) continue;
    els.tagNav.append(tagChip(path, {
      interactive: true,
      compact: true,
      active: path === state.activeTag
    }));
  }
}

function renderActiveTagFilter() {
  if (els.tagFilterInput) els.tagFilterInput.value = state.activeTag;
  if (!els.tagFilter) return;
  const active = Boolean(state.activeTag);
  els.tagFilter.hidden = !active;
  els.tagFilter.classList.toggle("d-none", !active);
  els.tagFilter.classList.toggle("d-flex", active);
  const label = els.tagFilter.querySelector("span");
  if (label) label.textContent = state.activeTag ? `Tag: ${state.activeTag}` : "";
  renderTagNavigation();
}

function renderTagSuggestions() {
  for (const target of els.tagSuggestions || []) {
    target.innerHTML = "";
    if (state.tags.length === 0) {
      target.hidden = true;
      continue;
    }
    target.hidden = false;
    const label = document.createElement("span");
    label.className = "tag-suggestions-title";
    label.textContent = "Known tags";
    target.append(label);
    const chips = document.createElement("div");
    chips.className = "tag-chip-list is-compact";
    for (const tag of state.tags.slice(0, 60)) {
      const path = tagPath(tag);
      if (!path) continue;
      const chip = tagChip(path, {
        interactive: true,
        compact: true,
        onClick: () => appendTagPath(target, path)
      });
      chips.append(chip);
    }
    target.append(chips);
  }
}

function appendTagPath(target, path) {
  const form = target.closest("form") || document;
  const input = form.querySelector("[data-tag-input]");
  if (!input) return;
  const values = normalizedTagValues(input.value);
  if (!values.includes(path)) values.push(path);
  input.value = values.join("\n");
  input.focus();
}

function normalizeTagInputs(root = document) {
  for (const input of root.querySelectorAll("[data-tag-input]")) {
    input.value = normalizedTagValues(input.value).join("\n");
  }
}

function normalizedTagValues(value) {
  const seen = new Set();
  const result = [];
  for (const raw of String(value || "").split(/[,\n]+/)) {
    const path = raw.trim();
    if (!path || seen.has(path)) continue;
    seen.add(path);
    result.push(path);
  }
  return result;
}

function tagChipList(tags, options = {}) {
  const wrap = document.createElement("div");
  wrap.className = "tag-chip-list";
  if (options.compact) wrap.classList.add("is-compact");
  for (const tag of tags) {
    const path = tagPath(tag);
    if (path) wrap.append(tagChip(path, options));
  }
  if (wrap.children.length === 0) wrap.hidden = true;
  return wrap;
}

function tagChip(path, options = {}) {
  const chip = document.createElement(options.interactive ? "button" : "span");
  chip.className = options.interactive
    ? "tag-chip btn btn-sm btn-outline-success"
    : "tag-chip badge rounded-pill text-bg-light border";
  if (options.compact) chip.classList.add("is-compact");
  if (options.active) chip.classList.add("is-active", "active");
  chip.textContent = path;
  if (options.interactive) {
    chip.type = "button";
    chip.addEventListener("click", event => {
      event.preventDefault();
      event.stopPropagation();
      if (typeof options.onClick === "function") {
        options.onClick(path);
      } else {
        location.href = `/web/blog/publicblogs?tag=${encodeURIComponent(path)}`;
      }
    });
  }
  return chip;
}

function postTags(post) {
  if (Array.isArray(post?.tags)) return post.tags;
  const text = post?.tagPaths || post?.tag_paths || "";
  return String(text).split(",").map(x => x.trim()).filter(Boolean).map(path => ({ path }));
}

function tagPath(tag) {
  if (!tag) return "";
  if (typeof tag === "string") return tag;
  return String(tag.path || tag.key || tag.name || tag.value || "").trim();
}

function showModalElement(element) {
  if (!element) return;
  const modal = window.bootstrap?.Modal?.getOrCreateInstance(element);
  if (modal) {
    modal.show();
  } else if (typeof element.showModal === "function") {
    element.showModal();
  } else {
    element.hidden = false;
  }
}

function hideModalElement(element) {
  if (!element) return;
  const modal = window.bootstrap?.Modal?.getInstance(element);
  if (modal) {
    modal.hide();
  } else if (typeof element.close === "function") {
    element.close();
  } else {
    element.hidden = true;
  }
}

function readId(record) {
  if (!record) return "";
  if (record.shortid) return String(record.shortid);
  if (record.shortId) return String(record.shortId);
  if (record.short_id) return String(record.short_id);
  if (record.entity_id) return String(record.entity_id);
  const id = record.id || record.blobId || "";
  return typeof id === "object" ? id.value || id.display || id.minor || String(id) : String(id);
}

function publicPostRef(post) {
  return post?.slug || post?.name || readId(post);
}

function editPostRef(post) {
  return post?.shortid || post?.shortId || post?.short_id || readId(post);
}

function statusText(post) {
  const status = post.postStatus || post.post_status || "";
  const active = post.aliveness || "";
  return [status, active].filter(Boolean).join(" / ");
}

function isPublicPost(post) {
  return (post.postStatus || post.post_status) === "published" &&
    post.aliveness === "alive";
}

function displayUser() {
  return state.session?.attributes?.login_name ||
    state.session?.attributes?.email ||
    state.session?.principalId ||
    "";
}

function notice(message, error = false) {
  if (!els.toast) return;
  els.toast.textContent = message;
  els.toast.classList.toggle("is-error", error);
  els.toast.hidden = false;
  window.clearTimeout(notice.timer);
  notice.timer = window.setTimeout(() => {
    els.toast.hidden = true;
  }, 3200);
}
