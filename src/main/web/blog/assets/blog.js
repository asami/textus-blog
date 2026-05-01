const paths = {
  session: "/web/blog/session",
  search: "/form-api/blog-component/blog/search-posts",
  get: "/form-api/blog-component/blog/get-post",
  save: "/form-api/blog-component/blog/save-editor-post",
  importTree: "/form-api/blog-component/blog/import-post-tree",
  images: "/form-api/blog-component/blog/list-image-blobs",
  jobs: "/rest/v1/job_control/job/await_job_result"
};

const state = {
  session: null,
  posts: [],
  currentPost: null
};

const els = {
  login: document.querySelector("[data-login-link]"),
  signup: document.querySelector("[data-signup-link]"),
  logout: document.querySelector("[data-logout-form]"),
  sessionName: document.querySelector("[data-session-name]"),
  searchForm: document.querySelector("[data-search-form]"),
  postList: document.querySelector("[data-post-list]"),
  articleTitle: document.querySelector("[data-article-title]"),
  articleBody: document.querySelector("[data-article-body]"),
  editorPane: document.querySelector("[data-editor-pane]"),
  editorForm: document.querySelector("[data-editor-form]"),
  uploadForm: document.querySelector("[data-upload-form]"),
  editorId: document.querySelector("[data-editor-id]"),
  editorSlug: document.querySelector("[data-editor-slug]"),
  editorTitle: document.querySelector("[data-editor-title]"),
  editorContent: document.querySelector("[data-editor-content]"),
  editorDescription: document.querySelector("[data-editor-description]"),
  editorPublish: document.querySelector("[data-editor-publish]"),
  imageDialog: document.querySelector("[data-image-dialog]"),
  imageGrid: document.querySelector("[data-image-grid]"),
  toast: document.querySelector("[data-toast]")
};

document.addEventListener("DOMContentLoaded", boot);

async function boot() {
  bindEvents();
  await loadSession();
  await loadPosts(new URLSearchParams(location.search).get("text") || "");
  const postId = new URLSearchParams(location.search).get("post") || location.hash.replace(/^#post=/, "");
  if (postId) {
    await openPost(postId);
  } else if (state.posts.length > 0) {
    await openPost(readId(state.posts[0]));
  }
}

function bindEvents() {
  els.searchForm.addEventListener("submit", async event => {
    event.preventDefault();
    await loadPosts(new FormData(els.searchForm).get("text") || "");
  });

  els.editorForm.addEventListener("submit", saveEditorPost);
  els.uploadForm.addEventListener("submit", importPostTree);

  document.querySelector("[data-new-post]").addEventListener("click", () => setEditor(null));
  document.querySelector("[data-open-image-picker]").addEventListener("click", openImagePicker);
  document.querySelectorAll("[data-editor-tab]").forEach(button => {
    button.addEventListener("click", () => switchEditorTab(button.dataset.editorTab));
  });
}

async function loadSession() {
  try {
    const response = await fetch(paths.session, { credentials: "same-origin" });
    state.session = response.ok ? await response.json() : { authenticated: false };
  } catch {
    state.session = { authenticated: false };
  }
  const authenticated = Boolean(state.session?.authenticated);
  els.login.hidden = authenticated;
  if (els.signup) els.signup.hidden = authenticated;
  els.logout.hidden = !authenticated;
  els.editorPane.hidden = !authenticated;
  els.sessionName.textContent = authenticated ? displayUser() : "";
}

async function loadPosts(text) {
  const form = new FormData();
  if (text) form.append("text", text);
  form.append("limit", "50");
  const result = await postForm(paths.search, form);
  state.posts = result.data || [];
  renderPostList();
}

function renderPostList() {
  els.postList.innerHTML = "";
  if (state.posts.length === 0) {
    els.postList.innerHTML = `<p class="post-row-meta">No posts.</p>`;
    return;
  }
  for (const post of state.posts) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "post-row";
    if (state.currentPost && readId(state.currentPost) === readId(post)) button.classList.add("is-active");
    button.addEventListener("click", () => openPost(readId(post)));

    const thumb = document.createElement("img");
    thumb.className = "post-thumb";
    thumb.alt = "";
    thumb.src = post.representativeBlobUrl || "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='72' height='72'%3E%3Crect width='72' height='72' fill='%23edf0f3'/%3E%3C/svg%3E";

    const text = document.createElement("div");
    text.innerHTML = `<div class="post-row-title"></div><div class="post-row-meta"></div>`;
    text.querySelector(".post-row-title").textContent = post.title || post.slug || readId(post);
    text.querySelector(".post-row-meta").textContent = post.slug || "";

    button.append(thumb, text);
    els.postList.append(button);
  }
}

async function openPost(id) {
  if (!id) return;
  const form = new FormData();
  form.append("id", id);
  const post = await postForm(paths.get, form);
  state.currentPost = post;
  els.articleTitle.textContent = post.title || post.slug || "";
  els.articleBody.innerHTML = post.content || "";
  setEditor(post);
  renderPostList();
  history.replaceState(null, "", `/web/blog?post=${encodeURIComponent(id)}`);
}

function setEditor(post) {
  if (!state.session?.authenticated) return;
  if (post && !canEditPost(post)) {
    els.editorId.value = "";
    els.editorSlug.value = "";
    els.editorTitle.value = "";
    els.editorContent.value = "<article>\n  <p></p>\n</article>";
    els.editorDescription.value = "";
    els.editorPublish.checked = false;
    return;
  }
  els.editorId.value = post ? readId(post) : "";
  els.editorSlug.value = post?.slug || "";
  els.editorTitle.value = post?.title || "";
  els.editorContent.value = post?.content || "<article>\n  <p></p>\n</article>";
  els.editorDescription.value = post?.description || "";
  els.editorPublish.checked = post?.draftStatus === "published";
}

function showEditorDraft(id, form) {
  const draft = {
    id,
    entity_id: id,
    slug: form.get("slug") || "",
    title: form.get("title") || "",
    content: form.get("content") || "",
    description: form.get("description") || "",
    draft_status: "draft",
    active_status: "active",
    author_account_id: currentUserAccountId()
  };
  state.currentPost = draft;
  els.editorId.value = id || "";
  els.articleTitle.textContent = draft.title || draft.slug || "";
  els.articleBody.innerHTML = draft.content || "";
  history.replaceState(null, "", "/web/blog");
}

async function saveEditorPost(event) {
  event.preventDefault();
  const form = new FormData(els.editorForm);
  if (!form.get("id") || (state.currentPost && !canEditPost(state.currentPost))) form.delete("id");
  if (!els.editorPublish.checked) form.delete("publish");
  try {
    const result = await postForm(paths.save, form);
    const savedId = result.entity_id || result.id;
    notice("Saved.");
    await loadPosts("");
    if (els.editorPublish.checked && savedId) {
      await openPost(savedId);
    } else {
      showEditorDraft(savedId, form);
    }
  } catch (error) {
    notice(error.message, true);
  }
}

async function importPostTree(event) {
  event.preventDefault();
  const form = new FormData(els.uploadForm);
  const publish = Boolean(form.get("publish"));
  if (!publish) form.delete("publish");
  try {
    const result = await postForm(paths.importTree, form);
    const savedId = result.entity_id || result.id;
    notice("Imported.");
    els.uploadForm.reset();
    await loadPosts("");
    if (publish && savedId) {
      await openPost(savedId);
    }
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
    els.imageDialog.showModal();
  } catch (error) {
    notice(error.message, true);
  }
}

function renderImages(images) {
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

function switchEditorTab(name) {
  document.querySelectorAll("[data-editor-tab]").forEach(button => {
    button.classList.toggle("is-active", button.dataset.editorTab === name);
  });
  els.editorForm.hidden = name !== "editor";
  els.uploadForm.hidden = name !== "upload";
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

function readId(record) {
  if (!record) return "";
  if (record.entity_id) return String(record.entity_id);
  const id = record.id || record.blobId || "";
  return typeof id === "object" ? id.value || id.display || id.minor || String(id) : String(id);
}

function displayUser() {
  return state.session?.attributes?.login_name ||
    state.session?.attributes?.email ||
    state.session?.principalId ||
    "";
}

function canEditPost(post) {
  const author = post?.author_account_id || post?.authorAccountId || "";
  const current = currentUserAccountId();
  return Boolean(author && current && String(author) === String(current));
}

function currentUserAccountId() {
  return state.session?.attributes?.user_account_id ||
    state.session?.attributes?.userAccountId ||
    state.session?.principalId ||
    "";
}

function notice(message, error = false) {
  els.toast.textContent = message;
  els.toast.classList.toggle("is-error", error);
  els.toast.hidden = false;
  window.clearTimeout(notice.timer);
  notice.timer = window.setTimeout(() => {
    els.toast.hidden = true;
  }, 3200);
}
