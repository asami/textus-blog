const paths = {
  session: "/web/blog/session",
  search: "/form-api/blog-component/blog/search-posts",
  get: "/form-api/blog-component/blog/get-post",
  save: "/form-api/blog-component/blog/save-editor-post",
  importTree: "/form-api/blog-component/blog/import-post-tree",
  images: "/form-api/blog-component/blog/list-image-blobs"
};

const state = {
  session: null,
  posts: [],
  currentPost: null
};

const els = {
  login: document.querySelector("[data-login-link]"),
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
  els.editorId.value = post ? readId(post) : "";
  els.editorSlug.value = post?.slug || "";
  els.editorTitle.value = post?.title || "";
  els.editorContent.value = post?.content || "<article>\n  <p></p>\n</article>";
  els.editorDescription.value = post?.description || "";
  els.editorPublish.checked = post?.draftStatus === "published";
}

async function saveEditorPost(event) {
  event.preventDefault();
  const form = new FormData(els.editorForm);
  if (!form.get("id")) form.delete("id");
  if (!els.editorPublish.checked) form.delete("publish");
  try {
    const result = await postForm(paths.save, form);
    notice("Saved.");
    await loadPosts("");
    await openPost(result.entity_id || result.id);
  } catch (error) {
    notice(error.message, true);
  }
}

async function importPostTree(event) {
  event.preventDefault();
  const form = new FormData(els.uploadForm);
  if (!form.get("publish")) form.delete("publish");
  try {
    const result = await postForm(paths.importTree, form);
    notice("Imported.");
    els.uploadForm.reset();
    await loadPosts("");
    await openPost(result.entity_id || result.id);
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
  const response = await fetch(url, {
    method: "POST",
    credentials: "same-origin",
    headers,
    body: form
  });
  const text = await response.text();
  const data = text ? JSON.parse(text) : {};
  if (!response.ok || data.status === "failure") {
    throw new Error(data.message || data.error || response.statusText);
  }
  return data.result || data;
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

function notice(message, error = false) {
  els.toast.textContent = message;
  els.toast.classList.toggle("is-error", error);
  els.toast.hidden = false;
  window.clearTimeout(notice.timer);
  notice.timer = window.setTimeout(() => {
    els.toast.hidden = true;
  }, 3200);
}
