const paths = {
  session: "/web/blog/session",
  search: "/form-api/blog-component/blog/search-posts",
  get: "/form-api/blog-component/blog/get-post",
  searchMy: "/form-api/blog-component/blog/search-my-posts",
  getMy: "/form-api/blog-component/blog/get-my-post",
  save: "/form-api/blog-component/blog/save-editor-post",
  importTree: "/form-api/blog-component/blog/import-post-tree",
  images: "/form-api/blog-component/blog/list-image-blobs",
  jobs: "/rest/v1/job_control/job/await_job_result"
};

const state = {
  session: null,
  posts: [],
  currentPost: null,
  page: document.body.dataset.page || "reader"
};

const els = {
  login: document.querySelector("[data-login-link]"),
  signup: document.querySelector("[data-signup-link]"),
  myPosts: document.querySelectorAll("[data-my-posts-link]"),
  logout: document.querySelector("[data-logout-form]"),
  sessionName: document.querySelector("[data-session-name]"),
  searchForm: document.querySelector("[data-search-form]"),
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
  editorContent: document.querySelector("[data-editor-content]"),
  editorDescription: document.querySelector("[data-editor-description]"),
  editorPublish: document.querySelector("[data-editor-publish]"),
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
  if (state.page === "my") {
    if (!requireAuth()) return;
    bindDashboardEvents();
    await loadMyPosts(new URLSearchParams(location.search).get("text") || "");
  } else if (state.page === "edit") {
    if (!requireAuth()) return;
    bindEditorEvents();
    await loadEditorPost();
  } else if (state.page === "reader") {
    bindReaderEvents();
    await loadPublicPosts(new URLSearchParams(location.search).get("text") || "");
    const postId = new URLSearchParams(location.search).get("post") || location.hash.replace(/^#post=/, "");
    if (postId) {
      await openPublicPost(postId);
    }
  }
}

function bindCommonEvents() {
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
    await loadPublicPosts(new FormData(els.searchForm).get("text") || "");
  });
}

function bindDashboardEvents() {
  els.searchForm?.addEventListener("submit", async event => {
    event.preventDefault();
    await loadMyPosts(new FormData(els.searchForm).get("text") || "");
  });
  document.querySelector("[data-open-upload-dialog]")?.addEventListener("click", () => {
    els.uploadDialog?.showModal();
  });
  els.uploadForm?.addEventListener("submit", importPostTree);
}

function bindEditorEvents() {
  document.querySelector("[data-open-image-picker]")?.addEventListener("click", openImagePicker);
}

async function loadSession() {
  try {
    const response = await fetch(paths.session, { credentials: "same-origin" });
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
}

function requireAuth() {
  if (state.session?.authenticated) return true;
  const returnTo = encodeURIComponent(`${location.pathname}${location.search}`);
  location.href = `/web/textus-user-account/signin?returnTo=${returnTo}`;
  return false;
}

async function loadPublicPosts(text) {
  const form = new FormData();
  if (text) form.append("text", text);
  form.append("limit", "50");
  const result = await postForm(paths.search, form);
  state.posts = result.data || [];
  renderPublicPostList();
}

async function loadMyPosts(text) {
  const form = new FormData();
  if (text) form.append("text", text);
  form.append("limit", "100");
  const result = await postForm(paths.searchMy, form);
  state.posts = result.data || [];
  renderMyPostList();
}

function renderPublicPostList() {
  if (!els.postList) return;
  els.postList.innerHTML = "";
  if (state.posts.length === 0) {
    els.postList.innerHTML = `<p class="post-row-meta">No posts.</p>`;
    return;
  }
  for (const post of state.posts) {
    const ref = publicPostRef(post);
    const button = document.createElement("button");
    button.type = "button";
    button.className = "post-row";
    if (state.currentPost && publicPostRef(state.currentPost) === ref) button.classList.add("is-active");
    button.addEventListener("click", () => openPublicPost(ref));
    button.append(postThumb(post), rowText(post.title || post.slug || readId(post), post.slug || ""));
    els.postList.append(button);
  }
}

function renderMyPostList() {
  if (!els.myPostList) return;
  els.myPostList.innerHTML = "";
  if (state.posts.length === 0) {
    els.myPostList.innerHTML = `<p class="post-row-meta">No posts.</p>`;
    return;
  }
  for (const post of state.posts) {
    const row = document.createElement("div");
    row.className = "post-row dashboard-row";
    const body = rowText(post.title || post.slug || readId(post), `${post.slug || ""} ${statusText(post)}`);
    const actions = document.createElement("div");
    actions.className = "post-actions";
    actions.append(linkButton("Edit", `/web/blog/update?id=${encodeURIComponent(editPostRef(post))}`));
    if (isPublicPost(post)) {
      actions.append(linkButton("View public", `/web/blog/publicblogs?post=${encodeURIComponent(publicPostRef(post))}`));
    }
    body.append(actions);
    row.append(postThumb(post), body);
    els.myPostList.append(row);
  }
}

async function openPublicPost(id) {
  if (!id) return;
  const form = new FormData();
  form.append("id", id);
  const post = await postForm(paths.get, form);
  state.currentPost = post;
  document.body.classList.add("reader-detail-mode");
  if (els.backToList) els.backToList.hidden = false;
  if (els.articleTitle) els.articleTitle.textContent = post.title || post.slug || "";
  if (els.articleBody) els.articleBody.innerHTML = post.content || "";
  renderPublicPostList();
  history.replaceState(null, "", `/web/blog/publicblogs?post=${encodeURIComponent(publicPostRef(post) || id)}`);
}

async function loadEditorPost() {
  const id = new URLSearchParams(location.search).get("id");
  if (!id) {
    setEditor(null);
    return;
  }
  const form = new FormData();
  form.append("id", id);
  const post = await postForm(paths.getMy, form);
  state.currentPost = post;
  setEditor(post);
}

function setEditor(post) {
  if (els.editorHeading) els.editorHeading.textContent = post ? "Edit post" : "New post";
  if (els.editorId) els.editorId.value = post ? readId(post) : "";
  if (els.editorSlug) els.editorSlug.value = post?.slug || "";
  if (els.editorTitle) els.editorTitle.value = post?.title || "";
  if (els.editorContent) els.editorContent.value = post?.content || "<article>\n  <p></p>\n</article>";
  if (els.editorDescription) els.editorDescription.value = post?.description || "";
  if (els.editorPublish) els.editorPublish.checked = post?.postStatus === "published" || post?.post_status === "published";
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
    els.uploadDialog?.close();
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
    els.imageDialog?.showModal();
  } catch (error) {
    notice(error.message, true);
  }
}

function renderImages(images) {
  if (!els.imageGrid) return;
  els.imageGrid.innerHTML = "";
  if (images.length === 0) {
    els.imageGrid.innerHTML = `<p class="post-row-meta">No images.</p>`;
    return;
  }
  for (const image of images) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "image-card";
    button.innerHTML = `<img alt=""><strong></strong><span class="image-id"></span>`;
    button.querySelector("img").src = image.url;
    button.querySelector("strong").textContent = image.filename || readId(image);
    button.querySelector(".image-id").textContent = readId(image);
    button.addEventListener("click", () => {
      insertAtCursor(els.editorContent, `<img src="/web/blob/content/${readId(image)}" alt="">`);
      els.imageDialog.close();
    });
    els.imageGrid.append(button);
  }
}

async function postForm(url, form) {
  const headers = {};
  if (state.session?.sessionId) headers["x-textus-session"] = state.session.sessionId;
  let response = await fetch(url, {
    method: "POST",
    credentials: "same-origin",
    headers,
    body: form
  });
  let text = await response.text();
  if (response.ok && isJobId(text)) {
    const awaited = await awaitJobResult(text.trim());
    response = awaited.response;
    text = awaited.text;
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

function postThumb(post) {
  const thumb = document.createElement("img");
  thumb.className = "post-thumb";
  thumb.alt = "";
  thumb.src = post.representativeBlobUrl || "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='72' height='72'%3E%3Crect width='72' height='72' fill='%23edf0f3'/%3E%3C/svg%3E";
  return thumb;
}

function rowText(title, meta) {
  const text = document.createElement("div");
  text.className = "post-row-body";
  text.innerHTML = `<div class="post-row-title"></div><div class="post-row-meta"></div>`;
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
