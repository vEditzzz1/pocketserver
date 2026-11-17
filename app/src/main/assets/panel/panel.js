/* PocketServer control panel — talks to the app's local HTTP API. */
(() => {
  const params = new URLSearchParams(location.search);
  const token = params.get('token') || localStorage.getItem('ps.token') || '';
  if (params.get('token')) localStorage.setItem('ps.token', params.get('token'));

  const $ = (id) => document.getElementById(id);
  const esc = (s) =>
    String(s == null ? '' : s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  if (!token) {
    document.body.innerHTML =
      '<div class="card"><h2>Token required</h2><p class="muted">Open this panel from the PocketServer app ' +
      '(it adds the token to the address).</p></div>';
    return;
  }

  async function api(path, extra = {}) {
    const q = new URLSearchParams({ token, ...extra });
    // Never leave a button stuck disabled if the app stops answering.
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20000);
    try {
      const res = await fetch(`${path}?${q.toString()}`, { cache: 'no-store', signal: controller.signal });
      if (!res.ok) {
        const text = await res.text().catch(() => '');
        let message = text;
        try {
          const parsed = JSON.parse(text);
          if (parsed && parsed.error) message = parsed.error;
        } catch (_) {}
        throw new Error(message || `HTTP ${res.status}`);
      }
      return await res.json();
    } catch (e) {
      throw new Error(e.name === 'AbortError' ? 'the app did not answer in time' : e.message);
    } finally {
      clearTimeout(timer);
    }
  }

  function logErr(e) {
    if (e && e.message) console.warn('panel:', e.message);
  }

  /* ------------------------------------------------------------- helpers */

  function el(tag, attrs = {}, kids = []) {
    const node = document.createElement(tag);
    Object.entries(attrs).forEach(([key, value]) => {
      if (value === null || value === undefined || value === false) return;
      if (key === 'class') node.className = value;
      else if (key === 'text') node.textContent = value;
      else if (key === 'checked' || key === 'disabled' || key === 'open') node[key] = Boolean(value);
      else if (key === 'value') node.value = value;
      else node.setAttribute(key, value);
    });
    (Array.isArray(kids) ? kids : [kids]).forEach((kid) => {
      if (kid === null || kid === undefined) return;
      node.appendChild(typeof kid === 'string' ? document.createTextNode(kid) : kid);
    });
    return node;
  }

  function field(label, control) {
    return el('label', { class: 'field' }, [el('span', { text: label }), control]);
  }

  function textInput(name, value, placeholder) {
    return el('input', {
      class: 'input',
      name,
      value: value || '',
      placeholder: placeholder || '',
      autocomplete: 'off',
      spellcheck: 'false',
    });
  }

  /** Environment rows of any form that uses the .envrow markup. */
  function readEnv(scope) {
    return [...scope.querySelectorAll('.envrow')]
      .map((row) => ({
        key: row.querySelector('[name=envkey]').value.trim(),
        value: row.querySelector('[name=envval]').value,
      }))
      .filter((row) => row.key);
  }

  /** Volume rows; `$SITE` is resolved by the app to the site folder. */
  function readVolumes(scope) {
    return [...scope.querySelectorAll('.volrow')]
      .map((row) => ({
        host: row.querySelector('[name=volhost]').value.trim(),
        guest: row.querySelector('[name=volguest]').value.trim(),
      }))
      .filter((row) => row.host && row.guest);
  }

  /* -------------------------------------------------------------- render */

  async function refresh() {
    try {
      render(await api('/api/state'));
    } catch (e) {
      $('host').textContent = 'connection lost — is the panel still running?';
    }
  }

  function render(state) {
    $('host').textContent = state.runtime
      ? `${state.host} · ${state.distroLabel || 'Linux'} ready · panel :${state.panelPort}`
      : 'runtime not installed';
    const mode = $('mode');
    mode.textContent = state.modeLabel || state.mode;
    mode.className = 'chip' + (state.runtime ? '' : ' warn');

    document.querySelectorAll('#modes button').forEach((b) => {
      b.setAttribute('aria-pressed', String(b.dataset.mode === state.mode));
    });

    const distroChip = $('distro-state');
    if (distroChip) {
      distroChip.textContent = state.distroLabel || '—';
      distroChip.className = 'chip' + (state.distro === 'ubuntu' ? ' ok' : '');
    }
    document.querySelectorAll('#distros button').forEach((b) => {
      b.setAttribute('aria-pressed', String(b.dataset.distro === state.distro));
    });
    currentDistro = state.distro || 'alpine';

    const packs = $('runtime-state');
    if (packs) {
      const n = (state.packages || []).length;
      packs.textContent = !state.runtime ? 'not installed' : n ? `${n} package set${n === 1 ? '' : 's'}` : 'no packages';
      packs.className = 'chip' + (state.runtime ? '' : ' warn');
    }

    const all = state.sites || [];
    renderSites(all.filter((s) => s.kind !== 'CONTAINER'));
    renderContainers(state.containers || all.filter((s) => s.kind === 'CONTAINER'));
    renderStacks(state.stacks || []);
    renderPodroid(state.podroid);
    renderGuide(state);
  }

  /**
   * Guide page: what the current combination means, what the app will refuse and
   * why. Built from the live state, so the page cannot drift from the engine.
   */
  function renderGuide(state) {
    const now = $('g-now');
    if (!now) return;
    const p = state.podroid || {};
    const sites = state.sites || [];
    const running = sites.filter((s) => s.running);

    const tiles = [
      ['Distro', state.distroLabel || '—'],
      ['Mode', state.modeLabel || state.mode || '—'],
      ['VM', p.running ? 'running — owns the ports' : 'stopped'],
      ['Sites', running.length ? `${running.length} running` : 'none running'],
      ['Panel', `:${state.panelPort}`],
      ['Sets', (state.packages || []).join(', ') || 'none'],
    ];
    now.innerHTML = tiles
      .map(([k, v]) => `<div class="tile"><span class="k">${esc(k)}</span><span class="v">${esc(v)}</span></div>`)
      .join('');

    const summary = $('g-summary');
    summary.textContent = p.running ? 'VM owns the ports' : state.distroLabel || '—';
    summary.className = 'chip' + (p.running ? ' ok' : ' warn');

    document.querySelectorAll('[data-guide-mode]').forEach((node) => {
      node.dataset.active = String(node.dataset.guideMode === state.mode);
    });
    document.querySelectorAll('[data-guide-distro]').forEach((node) => {
      node.dataset.active = String(node.dataset.guideDistro === state.distro);
    });

    const notes = [];
    const note = (text) => notes.push(text);
    if (!state.runtime) note('The runtime is not installed yet — install it on System first.');
    if (state.distro === 'ubuntu' && state.mode === 'Podroid') {
      note('Podroid mode is selected but the VM is Alpine: it refuses to boot, so sites fall back to PRoot on Ubuntu.');
    }
    if (state.distro === 'ubuntu' && p.running) {
      note('Ubuntu is active and the VM is running — that combination is not possible. Stop the VM.');
    }
    if (state.mode === 'Podroid' && !p.installed) {
      note('The VM images are not installed yet (306 MB one-time download). Install them on the Podroid page.');
    }
    if (state.mode === 'Podroid' && p.installed && !p.canHost) {
      note('Podroid needs Shizuku to start its kernel outside the app sandbox; without it sites fall back to PRoot.');
    }
    // Ports under 1024 need privileges, not a mode name: Root always has them,
    // and so does Shizuku when its server itself runs as UID 0. state.shizukuUid
    // is null when Shizuku is not usable, so that case stays pessimistic.
    const shizukuRoot = state.shizukuUid === 0;
    const privileged = state.mode === 'Root' || (state.mode === 'Shizuku' && shizukuRoot);
    const shizukuSummary = $('g-shizuku-summary');
    if (shizukuSummary) shizukuSummary.textContent = shizukuRoot ? 'Shizuku · root' : 'Shizuku · shell';
    const portCell = $('g-port-shizuku');
    if (portCell) {
      portCell.textContent = shizukuRoot ? '✔' : '✘';
      portCell.className = shizukuRoot ? 'yes' : 'no';
      portCell.title = shizukuRoot
        ? 'Shizuku server runs as root on this phone'
        : 'The Shizuku server runs as a normal user';
    }
    if (!privileged && running.some((s) => s.port < 1024)) {
      note('A site is on a port under 1024; only Root, or a Shizuku server running as root, can bind those.');
    }
    if (p.running && sites.some((s) => s.kind === 'CONTAINER')) {
      note('You have container sites and the VM is running: the VM does not enter a container rootfs, so those sites cannot serve.');
    }
    const forwarded = p.forwarded || [];
    if (p.running && running.some((s) => s.kind !== 'CONTAINER' && !forwarded.includes(s.port))) {
      note("A running site's port is not forwarded into the VM — start it again so the forward is added.");
    }

    const list = $('g-warn');
    list.innerHTML = notes.length
      ? notes.map((t) => `<li><span class="tag">note</span><span>${esc(t)}</span></li>`).join('')
      : '<li><span class="tag ok">ok</span><span>Nothing to flag — this combination is supported.</span></li>';
  }

  /**
   * Podroid page: state chip, stat tiles, power buttons and the forward list.
   * Every value comes from the VM itself, never from the selected runtime mode.
   */
  let fwdSig = '';
  let currentDistro = 'alpine';

  function renderPodroid(p) {
    const chip = $('pod-state');
    const info = $('pod-info');
    if (!chip || !info) return;
    if (!p) {
      chip.textContent = '—';
      chip.className = 'chip warn';
      return;
    }
    chip.textContent = !p.installed
      ? 'not installed'
      : p.busy
        ? 'working'
        : p.running
          ? 'running'
          : 'stopped';
    chip.className = 'chip' + (p.running ? '' : ' warn');

    const tiles = [
      ['Images', p.installed ? `${p.imagesMb} MB` : 'not installed'],
      ['Overlay', p.storageMb ? `${p.storageMb} MB` : 'not created'],
      ['Kernel pid', p.running && p.pid ? String(p.pid) : 'stopped'],
      ['Bridge', `127.0.0.1:${p.bridge}`],
      ['Site forwards', (p.forwarded || []).filter((x) => x !== p.bridge).join(', ') || 'none'],
      ['Host channel', p.canHost ? 'Shizuku' : 'unavailable'],
    ];
    info.innerHTML = tiles
      .map(
        ([k, v]) =>
          `<div class="tile"><span class="k">${esc(k)}</span><span class="v">${esc(v)}</span></div>`
      )
      .join('');

    const err = $('pod-err');
    err.classList.toggle('hidden', !p.error);
    err.textContent = p.error || '';

    $('pod-boot').disabled = !p.installed || p.running || p.busy || currentDistro === 'ubuntu';
    $('pod-stop').disabled = !p.running;
    $('pod-reboot').disabled = !p.running || currentDistro === 'ubuntu';
    $('pod-install').classList.toggle('hidden', p.installed || p.busy);
    $('pod-install').disabled = p.busy;
    $('pod-wipe').disabled = p.running;

    // The VM is an Alpine image; with Ubuntu selected it is not the guest.
    if (currentDistro === 'ubuntu' && !p.running) {
      $('pod-note').textContent =
        'Ubuntu 24.04 is the active distro — the VM runs Alpine, so it stays off. ' +
        'Switch to Alpine 3.24 on System to use it.';
    } else {
      $('pod-note').textContent =
        'A real Alpine kernel on Podroid\'s UML engine. While it runs it owns the forwarded ' +
        'site ports, so sites and guest commands run inside it.';
    }

    renderForwards(p);
  }

  /** Forward list; rebuilt only when the set changes, so a tap is not lost. */
  function renderForwards(p) {
    const list = $('pod-fwd');
    const count = $('pod-fwd-count');
    if (!list || !count) return;
    const ports = (p && p.forwarded ? p.forwarded : [])
      .filter((x) => x !== p.bridge)
      .sort((a, b) => a - b);
    count.textContent = ports.length ? `${ports.length} port${ports.length === 1 ? '' : 's'}` : 'none';
    count.className = 'chip' + (ports.length ? ' ok' : ' off');
    const sig = ports.join(',');
    if (sig === fwdSig) return;
    fwdSig = sig;
    list.innerHTML = '';
    if (!ports.length) {
      list.appendChild(el('li', { class: 'muted small', text: 'nothing forwarded yet' }));
      return;
    }
    ports.forEach((port) => {
      const remove = el('button', { class: 'btn ghost', type: 'button', text: 'Remove' });
      remove.onclick = async () => {
        remove.disabled = true;
        try {
          await api('/api/podroid/forward', { port: String(port), action: 'remove' });
        } catch (e) {
          logErr(e);
        }
        fwdSig = '';
        await refresh();
      };
      list.appendChild(
        el('li', { class: 'site' }, [
          el('span', { class: 'dot on' }),
          el('div', { class: 'meta' }, [
            el('div', { class: 'name', text: `:${port}` }),
            el('div', { class: 'sub', text: 'host → guest' }),
          ]),
          el('div', { class: 'actions' }, [remove]),
        ])
      );
    });
  }

  function renderSites(sites) {
    const list = $('sites');
    list.innerHTML = '';
    $('sites-empty').classList.toggle('hidden', sites.length > 0);

    sites.forEach((site) => {
      const li = document.createElement('li');
      li.className = 'site';
      li.innerHTML =
        `<span class="dot ${site.running ? 'on' : ''}"></span>` +
        `<div class="meta"><div class="name">${esc(site.name)}</div>` +
        `<div class="sub">${esc(site.kind)} · :${site.port}${site.running ? ' · ' + esc(site.url) : ''}</div></div>` +
        `<div class="actions"></div>`;
      const actions = li.querySelector('.actions');

      if (site.running) {
        const open = document.createElement('a');
        open.className = 'btn ghost';
        open.textContent = 'Open';
        open.href = site.url;
        open.target = '_blank';
        open.rel = 'noopener';
        actions.appendChild(open);
      }

      const toggle = document.createElement('button');
      toggle.className = 'btn ' + (site.running ? 'ghost' : '');
      toggle.textContent = site.running ? 'Stop' : 'Start';
      toggle.onclick = async () => {
        toggle.disabled = true;
        await api(`/api/site/${site.running ? 'stop' : 'start'}`, { id: site.id }).catch(logErr);
        await refresh();
      };
      actions.appendChild(toggle);

      const del = document.createElement('button');
      del.className = 'btn danger';
      del.textContent = 'Delete';
      del.onclick = async () => {
        if (!confirm(`Delete ${site.name}?`)) return;
        await api('/api/site/delete', { id: site.id }).catch(logErr);
        await refresh();
      };
      actions.appendChild(del);

      list.appendChild(li);
    });
  }

  /* ------------------------------------------------------------ catalog */

  let catalogEntries = [];
  let catalogCategory = '';
  let catalogQuery = '';
  let catalogOpenId = null;
  let catalogTimer = 0;

  async function loadCatalog() {
    const grid = $('cat-grid');
    if (!grid) return;
    grid.setAttribute('aria-busy', 'true');
    try {
      const data = await api('/api/catalog', {
        q: catalogQuery,
        category: catalogCategory,
        remote: $('cat-remote').checked && catalogQuery.trim() ? '1' : '0',
      });
      catalogEntries = data.images || [];
      renderCategories(data.categories || []);
      $('cat-source').textContent = data.remote ? 'built-in + Docker Hub' : 'built-in';
      $('cat-source').className = 'chip' + (data.remote ? '' : ' warn');
      const note = $('cat-note');
      note.textContent = data.note || '';
      note.classList.toggle('hidden', !data.note);
      renderCatalog();
    } catch (e) {
      $('cat-note').textContent = e.message;
      $('cat-note').classList.remove('hidden');
    } finally {
      grid.removeAttribute('aria-busy');
    }
  }

  function renderCategories(categories) {
    const wrap = $('cat-cats');
    if (!wrap) return;
    const sig = categories.map((c) => c.id).join(',');
    if (wrap.dataset.sig === sig) {
      wrap.querySelectorAll('button').forEach((b) => {
        b.setAttribute('aria-pressed', String(b.dataset.cat === catalogCategory));
      });
      return;
    }
    wrap.dataset.sig = sig;
    wrap.innerHTML = '';
    const mk = (id, label) => {
      const b = el('button', { class: 'btn ghost', type: 'button', text: label, 'data-cat': id });
      b.setAttribute('aria-pressed', String(id === catalogCategory));
      b.onclick = () => {
        catalogCategory = id;
        loadCatalog();
      };
      wrap.appendChild(b);
    };
    mk('', 'All');
    categories.forEach((c) => mk(c.id, c.label));
  }

  function renderCatalog() {
    const grid = $('cat-grid');
    if (!grid) return;
    grid.innerHTML = '';
    if (!catalogEntries.length) {
      grid.appendChild(el('p', { class: 'muted small', text: 'Nothing matches that search.' }));
      return;
    }
    catalogEntries.forEach((entry) => grid.appendChild(catalogCard(entry)));
    if (catalogOpenId) {
      const entry = catalogEntries.find((e) => e.id === catalogOpenId);
      if (entry) openCatalogForm(entry);
    }
  }

  function catalogCard(entry) {
    const tile = el('span', { class: 'cattile' });
    tile.style.setProperty('--c', entry.color || '#8FA3B8');
    if (entry.icon) {
      const img = el('img', { src: entry.icon, alt: '', loading: 'lazy' });
      img.addEventListener('error', () => tile.classList.add('broken'));
      tile.appendChild(img);
    } else {
      tile.classList.add('broken');
    }
    tile.appendChild(el('span', { class: 'mono', text: entry.mono || '?' }));

    const name = el('span', { class: 'catname' }, [document.createTextNode(entry.name)]);
    if (entry.official) name.appendChild(el('em', { text: 'official' }));

    const card = el(
      'button',
      {
        class: 'catcard',
        type: 'button',
        'aria-pressed': String(entry.id === catalogOpenId),
        'aria-label': `Configure ${entry.name}`,
      },
      [
        tile,
        el('span', { class: 'catmeta' }, [
          name,
          el('span', { class: 'catdesc', text: entry.description || entry.image }),
        ]),
        el('span', { class: 'catadd', text: 'Add' }),
      ]
    );
    card.onclick = () => {
      catalogOpenId = catalogOpenId === entry.id ? null : entry.id;
      renderCatalog();
    };
    return card;
  }

  function openCatalogForm(entry) {
    const panel = $('cat-form');
    panel.innerHTML = '';
    panel.classList.remove('hidden');

    const form = {
      dirty: false,
      image: textInput('image', entry.image, 'nginx:alpine'),
      port: el('input', {
        class: 'input',
        name: 'port',
        type: 'number',
        min: '1',
        max: '65535',
        inputmode: 'numeric',
        value: entry.port ? String(entry.port) : '8100',
      }),
      command: textInput('command', entry.command, "nginx -g 'daemon off;'"),
      workdir: textInput('workdir', entry.workdir || '/', '/'),
      user: textInput('user', entry.user || '0:0', '0:0'),
      envRows: el('div', { class: 'rows' }),
      volRows: el('div', { class: 'rows' }),
      err: el('p', { class: 'formerr', role: 'alert' }),
    };

    const addEnv = el('button', { class: 'btn ghost', type: 'button', text: '+ Add variable' });
    const addVol = el('button', { class: 'btn ghost', type: 'button', text: '+ Add volume' });
    const createStart = el('button', { class: 'btn', type: 'button', text: 'Create & start' });
    const createOnly = el('button', { class: 'btn ghost', type: 'button', text: 'Create only' });
    const cancel = el('button', { class: 'btn ghost', type: 'button', text: 'Cancel' });

    [form.image, form.command, form.workdir, form.user, form.port].forEach((input) =>
      input.addEventListener('input', () => {
        form.dirty = true;
      })
    );

    (entry.env || []).forEach((row) => addEnvRow(form, row.key, row.value));
    if (!(entry.env || []).length) addEnvRow(form, '', '');
    (entry.volumes || []).forEach((v) => addVolRow(form, v.host, v.guest));
    if (!(entry.volumes || []).length) addVolRow(form, '', '');

    addEnv.onclick = () => {
      addEnvRow(form, '', '');
      form.dirty = true;
    };
    addVol.onclick = () => {
      addVolRow(form, '', '');
      form.dirty = true;
    };
    cancel.onclick = () => {
      catalogOpenId = null;
      panel.classList.add('hidden');
      renderCatalog();
    };
    createOnly.onclick = () => createFromCatalog(entry, form, false, createOnly);
    createStart.onclick = () => createFromCatalog(entry, form, true, createStart);

    panel.appendChild(el('h3', { text: `New container — ${entry.name}` }));
    panel.appendChild(
      el('div', { class: 'grid2' }, [field('Image', form.image), field('Port', form.port)])
    );
    panel.appendChild(field('Command', form.command));
    panel.appendChild(
      el('div', { class: 'grid2' }, [
        field('Working directory', form.workdir),
        field('Run as (uid:gid)', form.user),
      ])
    );
    panel.appendChild(el('div', { class: 'field' }, [el('span', { text: 'Environment' }), form.envRows, addEnv]));
    panel.appendChild(
      el('div', { class: 'field' }, [
        el('span', { text: 'Volumes' }),
        el('p', { class: 'note', text: '$SITE is the site folder; the folder is also always mounted at /site.' }),
        form.volRows,
        addVol,
      ])
    );
    if (entry.note) panel.appendChild(el('p', { class: 'note', text: entry.note }));
    panel.appendChild(el('div', { class: 'wrap' }, [createStart, createOnly, cancel]));
    panel.appendChild(form.err);
  }

  async function createFromCatalog(entry, form, start, button) {
    form.err.textContent = '';
    button.disabled = true;
    try {
      const created = await api('/api/catalog/add', { id: entry.id, port: form.port.value });
      // The app resolves a busy port itself; its choice wins over the form.
      const port = created.port || Number(form.port.value) || 0;
      await api('/api/container/config', {
        id: created.id,
        data: JSON.stringify({
          image: form.image.value.trim(),
          platform: '',
          command: form.command.value,
          entrypoint: '',
          workdir: form.workdir.value.trim(),
          user: form.user.value.trim(),
          port,
          autoStart: false,
          env: readEnv(form.envRows),
          volumes: readVolumes(form.volRows),
        }),
      });
      if (start) await api('/api/site/start', { id: created.id });
      catalogOpenId = null;
      $('cat-form').classList.add('hidden');
      await refresh();
    } catch (e) {
      form.err.textContent = e.message;
    } finally {
      button.disabled = false;
    }
  }

  /* ---------------------------------------------------------- containers */

  // One editor per container, reused across refreshes so an open form and
  // anything being typed into it survives the state stream (every ~1.2 s).
  const editors = new Map();

  function renderContainers(containers) {
    const ul = $('containers');
    if (!ul) return;
    $('containers-empty').classList.toggle('hidden', containers.length > 0);

    const seen = new Set();
    containers.forEach((site) => {
      seen.add(site.id);
      let ed = editors.get(site.id);
      if (!ed) {
        ed = buildContainer(site);
        editors.set(site.id, ed);
        ul.appendChild(ed.node);
      }
      updateSummary(ed, site);
      if (!ed.dirty) fillForm(ed, site);
    });

    editors.forEach((ed, id) => {
      if (seen.has(id)) return;
      ed.node.remove();
      editors.delete(id);
    });
  }

  function buildContainer(site) {
    const id = site.id;

    const dot = el('span', { class: 'dot' });
    const nameEl = el('span', { class: 'name' });
    const sub = el('span', { class: 'sub' });
    const summary = el('summary', {}, [
      dot,
      el('span', { class: 'meta' }, [nameEl, sub]),
      el('span', { class: 'chev', text: '›', 'aria-hidden': 'true' }),
    ]);

    const f = {
      image: textInput('image', '', 'alpine:3.20'),
      platform: el('select', { class: 'input', name: 'platform' }, [
        el('option', { value: '', text: 'auto (device arch)' }),
        el('option', { value: 'linux/arm64', text: 'linux/arm64' }),
        el('option', { value: 'linux/amd64', text: 'linux/amd64 (needs qemu)' }),
      ]),
      command: textInput('command', '', "nginx -g 'daemon off;'"),
      entrypoint: textInput('entrypoint', '', 'optional, e.g. /docker-entrypoint.sh'),
      workdir: textInput('workdir', '/', '/'),
      user: textInput('user', '0:0', '0:0'),
      port: el('input', {
        class: 'input',
        name: 'port',
        type: 'number',
        min: '1',
        max: '65535',
        inputmode: 'numeric',
        placeholder: '8080',
      }),
      autoStart: el('input', { type: 'checkbox', name: 'autoStart' }),
    };

    const envRows = el('div', { class: 'rows' });
    const volRows = el('div', { class: 'rows' });
    const err = el('p', { class: 'formerr', role: 'alert' });
    const saved = el('p', { class: 'saved' });
    const pre = el('pre', { class: 'pre' });

    const addEnv = el('button', { class: 'btn ghost', type: 'button', text: '+ Add variable' });
    const addVol = el('button', { class: 'btn ghost', type: 'button', text: '+ Add volume' });
    const save = el('button', { class: 'btn', type: 'button', text: 'Save' });
    const run = el('button', { class: 'btn ghost', type: 'button', text: 'Run' });
    const open = el('a', {
      class: 'btn ghost hidden',
      target: '_blank',
      rel: 'noopener',
      text: 'Open',
      href: '#',
    });
    const logs = el('button', { class: 'btn ghost', type: 'button', text: 'Logs' });
    const inspect = el('button', { class: 'btn ghost', type: 'button', text: 'Inspect' });
    const recreate = el('button', { class: 'btn danger', type: 'button', text: 'Recreate from image' });
    const del = el('button', { class: 'btn danger', type: 'button', text: 'Delete' });

    const body = el('div', { class: 'ctrbody' }, [
      el('div', { class: 'grid2' }, [field('Image', f.image), field('Platform', f.platform)]),
      field('Command', f.command),
      field('Entrypoint', f.entrypoint),
      el('div', { class: 'grid2' }, [
        field('Working directory', f.workdir),
        field('Run as (uid:gid)', f.user),
      ]),
      el('div', { class: 'grid2' }, [
        field('Port', f.port),
        el('label', { class: 'check' }, [f.autoStart, el('span', { text: 'Start with the runtime' })]),
      ]),
      el('div', { class: 'field' }, [el('span', { text: 'Environment' }), envRows, addEnv]),
      el('div', { class: 'field' }, [
        el('span', { text: 'Volumes' }),
        el('p', { class: 'note', text: 'Host path on the phone → path inside the container. $SITE is the site folder (always mounted at /site too); $PS_PORT is the site port, which the process must listen on.' }),
        volRows,
        addVol,
      ]),
      el('div', { class: 'wrap' }, [save, run, open, logs, inspect, recreate, del]),
      err,
      saved,
      pre,
      el('p', {
        class: 'note',
        html:
          '<b>Not available under PRoot:</b> hostname, network modes, cgroups or CPU/memory limits, ' +
          'read-only volumes and SysV IPC. Ports are not remapped — the process binds the phone port directly.',
      }),
    ]);

    const node = el('li', { class: 'ctr' }, [el('details', {}, [summary, body])]);

    const ed = {
      id,
      node,
      body,
      dot,
      nameEl,
      sub,
      f,
      envRows,
      volRows,
      err,
      saved,
      pre,
      save,
      run,
      open,
      logs,
      inspect,
      recreate,
      del,
      addEnv,
      addVol,
      dirty: false,
      running: false,
      name: site.name,
      image: site.image || '',
      url: site.url || '',
    };

    [f.image, f.command, f.entrypoint, f.workdir, f.user, f.port].forEach((input) => {
      input.addEventListener('input', () => {
        ed.dirty = true;
      });
    });
    [f.platform, f.autoStart].forEach((input) => {
      input.addEventListener('change', () => {
        ed.dirty = true;
      });
    });

    addEnv.onclick = () => {
      addEnvRow(ed, '', '');
      ed.dirty = true;
    };
    addVol.onclick = () => {
      addVolRow(ed, '', '');
      ed.dirty = true;
    };

    save.onclick = async () => {
      err.textContent = '';
      saved.textContent = '';
      save.disabled = true;
      try {
        const res = await api('/api/container/config', { id, data: JSON.stringify(collect(ed)) });
        ed.dirty = false;
        saved.textContent =
          res.site && res.site.running ? 'saved — restart it to apply' : 'configuration saved';
        setTimeout(() => {
          saved.textContent = '';
        }, 3000);
        await refresh();
      } catch (e) {
        err.textContent = e.message;
      } finally {
        save.disabled = false;
      }
    };

    run.onclick = async () => {
      err.textContent = '';
      run.disabled = true;
      try {
        await api(ed.running ? '/api/container/stop' : '/api/site/start', { id });
        await refresh();
      } catch (e) {
        err.textContent = e.message;
      } finally {
        run.disabled = false;
      }
    };

    logs.onclick = async () => {
      err.textContent = '';
      try {
        const data = await api('/api/logs', { id });
        const lines = data.lines || [];
        pre.textContent = lines.length ? lines.join('\n') : 'no output yet';
      } catch (e) {
        err.textContent = e.message;
      }
    };

    inspect.onclick = async () => {
      err.textContent = '';
      pre.textContent = 'inspecting…';
      try {
        const data = await api('/api/container/inspect', { id });
        pre.textContent =
          `container: ${data.container}\n` +
          `unpacked:  ${data.installed ? 'yes' : 'no'}\n` +
          `kernel:    ${data.capabilities}\n\n` +
          data.info;
      } catch (e) {
        pre.textContent = '';
        err.textContent = e.message;
      }
    };

    // Recreate drops container-local changes, so it asks twice.
    let armed = false;
    recreate.onclick = async () => {
      if (!armed) {
        armed = true;
        recreate.textContent = 'Tap again to recreate';
        setTimeout(() => {
          armed = false;
          recreate.textContent = 'Recreate from image';
        }, 5000);
        return;
      }
      armed = false;
      recreate.textContent = 'Recreate from image';
      err.textContent = '';
      try {
        await api('/api/container/recreate', { id, confirm: '1' });
        pre.textContent = `recreating ${ed.image} — watch the console`;
      } catch (e) {
        err.textContent = e.message;
      }
    };

    del.onclick = async () => {
      if (!confirm(`Remove ${ed.name} (${ed.image || 'image'})?`)) return;
      try {
        await api('/api/container/remove', { id });
        editors.delete(id);
        node.remove();
        await refresh();
      } catch (e) {
        err.textContent = e.message;
      }
    };

    return ed;
  }

  function updateSummary(ed, site) {
    ed.running = !!site.running;
    ed.name = site.name;
    ed.image = site.image || '';
    ed.url = site.url || '';
    ed.dot.className = 'dot' + (site.running ? ' on' : '');
    ed.nameEl.textContent = site.name;
    const state = site.running ? 'running' : site.installed ? 'stopped' : 'not unpacked';
    ed.sub.textContent = `${site.image || 'image'} · :${site.port} · ${state}`;
    ed.run.textContent = site.running ? 'Stop' : 'Run';
    if (site.running) {
      ed.open.href = site.url;
      ed.open.classList.remove('hidden');
    } else {
      ed.open.classList.add('hidden');
    }
  }

  function fillForm(ed, site) {
    const c = site.container || {};
    ed.f.image.value = site.image || '';
    ed.f.platform.value = c.platform || '';
    ed.f.command.value = site.body || '';
    ed.f.entrypoint.value = c.entrypoint || '';
    ed.f.workdir.value = c.workdir || '/';
    ed.f.user.value = c.user || '0:0';
    ed.f.port.value = site.port || '';
    ed.f.autoStart.checked = !!site.autoStart;

    ed.envRows.innerHTML = '';
    const env = c.env || [];
    env.forEach((row) => addEnvRow(ed, row.key, row.value));
    if (!env.length) addEnvRow(ed, '', '');

    ed.volRows.innerHTML = '';
    const volumes = c.volumes || [];
    volumes.forEach((v) => addVolRow(ed, v.host, v.guest));
    if (!volumes.length) addVolRow(ed, '', '');
  }

  function addEnvRow(ed, key, value) {
    const k = textInput('envkey', key, 'KEY');
    const v = textInput('envval', value, 'value');
    const rm = el('button', { class: 'btn rm', type: 'button', text: '×', 'aria-label': 'Remove variable' });
    const row = el('div', { class: 'rowedit envrow' }, [k, v, rm]);
    [k, v].forEach((input) =>
      input.addEventListener('input', () => {
        ed.dirty = true;
      })
    );
    rm.onclick = () => {
      row.remove();
      ed.dirty = true;
    };
    ed.envRows.appendChild(row);
  }

  function addVolRow(ed, host, guest) {
    const h = textInput('volhost', host, '/storage/emulated/0/… or $SITE');
    const g = textInput('volguest', guest, '/data');
    const rm = el('button', { class: 'btn rm', type: 'button', text: '×', 'aria-label': 'Remove volume' });
    const row = el('div', { class: 'rowedit volrow' }, [h, g, rm]);
    [h, g].forEach((input) =>
      input.addEventListener('input', () => {
        ed.dirty = true;
      })
    );
    rm.onclick = () => {
      row.remove();
      ed.dirty = true;
    };
    ed.volRows.appendChild(row);
  }

  function collect(ed) {
    return {
      image: ed.f.image.value.trim(),
      platform: ed.f.platform.value,
      command: ed.f.command.value,
      entrypoint: ed.f.entrypoint.value,
      workdir: ed.f.workdir.value.trim(),
      user: ed.f.user.value.trim(),
      port: Number(ed.f.port.value) || 0,
      autoStart: ed.f.autoStart.checked,
      env: readEnv(ed.body),
      volumes: readVolumes(ed.body),
    };
  }

  /* ------------------------------------------------------------ packages */

  const pkgRows = new Map();
  let pkgSig = null;
  let stackStatus = new Map();

  function renderStacks(stacks) {
    const list = $('pkg-list');
    if (!list) return;
    const recorded = stacks.filter((s) => s.recorded);
    const sig = stacks.map((s) => `${s.id}${s.recorded ? '+' : '-'}`).join(',');
    if (sig !== pkgSig) {
      pkgSig = sig;
      list.innerHTML = '';
      pkgRows.clear();
      recorded.forEach((stack) => {
        const row = buildPkgRow(stack);
        pkgRows.set(stack.id, row);
        list.appendChild(row.node);
      });
      $('pkg-empty').classList.toggle('hidden', recorded.length > 0);
      $('pkg-count').textContent = recorded.length
        ? `${recorded.length} set${recorded.length === 1 ? '' : 's'}`
        : 'none';
      renderAvailable(stacks);
    }
    stacks.forEach((stack) => {
      const row = pkgRows.get(stack.id);
      if (row) row.setStatus(stack);
    });
  }

  function renderAvailable(stacks) {
    const wrap = $('pkg-available');
    if (!wrap) return;
    wrap.innerHTML = '';
    const missing = stacks.filter((s) => !s.recorded);
    if (!missing.length) {
      wrap.appendChild(el('p', { class: 'muted small', text: 'Every package set is installed.' }));
      return;
    }
    missing.forEach((stack) => {
      const btn = el('button', { class: 'btn', type: 'button', text: stack.label });
      btn.onclick = async () => {
        btn.disabled = true;
        try {
          await api('/api/stack', { id: stack.id });
        } catch (e) {
          btn.disabled = false;
        }
        setTimeout(() => {
          btn.disabled = false;
        }, 5000);
      };
      wrap.appendChild(btn);
    });
  }

  function buildPkgRow(stack) {
    const chip = el('span', { class: 'chip off', text: 'recorded' });
    const sub = el('span', { class: 'sub' });
    const un = el('button', { class: 'btn danger', type: 'button', text: 'Uninstall' });
    const node = el('li', { class: 'pkg' }, [
      el('span', { class: 'meta' }, [el('span', { class: 'name', text: stack.label }), sub]),
      chip,
      el('span', { class: 'actions' }, [un]),
    ]);

    const row = {
      node,
      armed: false,
      setStatus(current) {
        const probed = stackStatus.get(current.id);
        if (probed) {
          chip.textContent = probed.present ? 'installed' : 'missing';
          chip.className = 'chip ' + (probed.present ? 'ok' : 'warn');
        } else {
          chip.textContent = 'recorded';
          chip.className = 'chip off';
        }
        const packages = (current.packages || []).join(' ');
        const sites = (current.sites || []).length ? ' · used by ' + current.sites.join(', ') : '';
        sub.textContent = packages + sites;
      },
    };

    un.onclick = async () => {
      if (!row.armed) {
        row.armed = true;
        un.textContent = 'Tap again';
        setTimeout(() => {
          row.armed = false;
          un.textContent = 'Uninstall';
        }, 5000);
        return;
      }
      row.armed = false;
      un.disabled = true;
      un.textContent = 'Removing…';
      try {
        await api('/api/stack/remove', { id: stack.id, confirm: '1' });
        stackStatus.delete(stack.id);
        pkgSig = null;
        await refresh();
      } catch (e) {
        un.textContent = 'Uninstall';
      } finally {
        un.disabled = false;
      }
    };

    return row;
  }

  /* -------------------------------------------------------------- wiring */

  $('cat-q').addEventListener('input', () => {
    catalogQuery = $('cat-q').value;
    clearTimeout(catalogTimer);
    catalogTimer = setTimeout(loadCatalog, 350);
  });
  $('cat-remote').addEventListener('change', loadCatalog);
  $('cat-reload').addEventListener('click', loadCatalog);

  $('pkg-check').addEventListener('click', async () => {
    const btn = $('pkg-check');
    btn.disabled = true;
    btn.textContent = 'Checking…';
    try {
      const data = await api('/api/stacks/status');
      stackStatus = new Map((data.stacks || []).map((s) => [s.id, s]));
      pkgSig = null;
      renderStacks(data.stacks || []);
    } catch (e) {
      logErr(e);
    } finally {
      btn.disabled = false;
      btn.textContent = 'Check status';
    }
  });

  const runBtn = $('ctr-run');
  if (runBtn) {
    runBtn.onclick = async () => {
      const image = $('ctr-image').value.trim();
      const command = $('ctr-command').value.trim();
      if (!image) return;
      runBtn.disabled = true;
      try {
        await api('/api/container/run', { image, command });
      } catch (e) {
        logErr(e);
        $('host').textContent = e.message;
      }
      setTimeout(() => {
        runBtn.disabled = false;
      }, 3000);
      await refresh();
    };
  }

  $('new-site').onclick = async () => {
    await api('/api/site/create').catch(logErr);
    await refresh();
  };

  // Runtime repair is safe; a reset asks for a second tap.
  const resetBtn = $('runtime-reset');
  const resetLabel = resetBtn.textContent;
  let resetArmed = false;
  resetBtn.onclick = async () => {
    if (!resetArmed) {
      resetArmed = true;
      resetBtn.textContent = 'Tap again to reset';
      setTimeout(() => {
        resetArmed = false;
        resetBtn.textContent = resetLabel;
      }, 5000);
      return;
    }
    resetArmed = false;
    resetBtn.textContent = resetLabel;
    await api('/api/runtime/reset').catch(logErr);
    await refresh();
  };
  $('runtime-repair').onclick = async () => {
    await api('/api/runtime/repair').catch(logErr);
    await refresh();
  };

  document.querySelectorAll('#modes button').forEach((btn) => {
    btn.onclick = async () => {
      await api('/api/mode', { set: btn.dataset.mode }).catch(logErr);
      await refresh();
    };
  });

  // Switching distro stops the VM and the sites in the app, so the button says
  // what is happening instead of looking stuck.
  document.querySelectorAll('#distros button').forEach((btn) => {
    btn.onclick = async () => {
      const label = btn.textContent;
      btn.disabled = true;
      btn.textContent = 'Switching…';
      try {
        await api('/api/distro', { set: btn.dataset.distro });
      } catch (e) {
        logErr(e);
      } finally {
        btn.textContent = label;
        btn.disabled = false;
        setTimeout(refresh, 1500);
      }
    };
  });

  $('pod-boot').addEventListener('click', async () => {
    const btn = $('pod-boot');
    btn.disabled = true;
    btn.textContent = 'Starting…';
    try {
      await api('/api/podroid/boot');
      podNote('booting — the kernel takes ~40 s, watch the console on System');
    } catch (e) {
      logErr(e);
      podNote(e.message);
    } finally {
      btn.textContent = 'Boot VM';
      setTimeout(refresh, 1500);
    }
  });

  $('pod-stop').addEventListener('click', async () => {
    const btn = $('pod-stop');
    btn.disabled = true;
    btn.textContent = 'Stopping…';
    try {
      await api('/api/podroid/stop');
    } catch (e) {
      logErr(e);
    } finally {
      btn.textContent = 'Stop VM';
      setTimeout(refresh, 1200);
    }
  });

  $('pod-reboot').addEventListener('click', async () => {
    const btn = $('pod-reboot');
    btn.disabled = true;
    btn.textContent = 'Rebooting…';
    podNote('rebooting the guest…');
    try {
      await api('/api/podroid/reboot');
    } catch (e) {
      logErr(e);
      podNote(e.message);
    } finally {
      btn.textContent = 'Reboot';
      setTimeout(refresh, 3000);
    }
  });

  $('pod-install').addEventListener('click', async () => {
    const btn = $('pod-install');
    btn.disabled = true;
    btn.textContent = 'Downloading…';
    podNote('downloading Podroid images (~325 MB, one time)');
    try {
      await api('/api/podroid/install');
    } catch (e) {
      logErr(e);
      podNote(e.message);
    } finally {
      btn.textContent = 'Install images';
      setTimeout(refresh, 2000);
    }
  });

  function podNote(text) {
    const out = $('pod-out');
    out.classList.remove('hidden');
    out.textContent = text;
  }

  /** Runs one command in the guest through the app's direct VM channel. */
  async function guestCmd(cmd) {
    const run = $('pod-run');
    const rc = $('pod-rc');
    run.disabled = true;
    rc.textContent = 'running…';
    rc.className = 'chip warn';
    podNote(`$ ${cmd}\nrunning in the guest…`);
    try {
      const res = await api('/api/podroid/exec', { cmd });
      const body = (res.output || '').trim();
      podNote(`$ ${cmd}\n${body || '(no output)'}\n\nexit ${res.rc}`);
      rc.textContent = res.rc === 0 ? 'ok' : `exit ${res.rc}`;
      rc.className = 'chip ' + (res.rc === 0 ? 'ok' : 'warn');
    } catch (e) {
      podNote(`$ ${cmd}\n${e.message}`);
      rc.textContent = 'failed';
      rc.className = 'chip warn';
    } finally {
      run.disabled = false;
    }
  }

  // Command history, so ↑ brings back the last thing typed.
  let cmdHistory = [];
  try {
    cmdHistory = JSON.parse(localStorage.getItem('ps.cmds') || '[]');
  } catch (_) {}
  let cmdAt = -1;

  function rememberCmd(cmd) {
    cmdHistory = [cmd, ...cmdHistory.filter((c) => c !== cmd)].slice(0, 30);
    cmdAt = -1;
    try {
      localStorage.setItem('ps.cmds', JSON.stringify(cmdHistory));
    } catch (_) {}
  }

  const podInput = $('pod-cmd');
  podInput.addEventListener('keydown', (ev) => {
    if (ev.key !== 'ArrowUp' && ev.key !== 'ArrowDown') return;
    if (!cmdHistory.length) return;
    ev.preventDefault();
    cmdAt =
      ev.key === 'ArrowUp'
        ? Math.min(cmdAt + 1, cmdHistory.length - 1)
        : Math.max(cmdAt - 1, -1);
    podInput.value = cmdAt >= 0 ? cmdHistory[cmdAt] : '';
  });

  $('pod-form').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const cmd = podInput.value.trim();
    if (!cmd) return;
    rememberCmd(cmd);
    guestCmd(cmd);
  });

  document.querySelectorAll('#pod-quick .chipbtn').forEach((chip) => {
    chip.onclick = () => guestCmd(chip.dataset.cmd);
  });

  $('pod-fwd-form').addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const port = $('pod-fwd-port').value.trim();
    if (!port) return;
    try {
      await api('/api/podroid/forward', { port, action: 'add' });
      $('pod-fwd-port').value = '';
      fwdSig = '';
    } catch (e) {
      podNote(e.message);
    }
    await refresh();
  });

  $('pod-vmlog').addEventListener('click', async () => {
    const out = $('pod-vmlog-out');
    const btn = $('pod-vmlog');
    out.classList.remove('hidden');
    out.textContent = 'reading vm.log…';
    btn.disabled = true;
    try {
      const res = await api('/api/podroid/log', { lines: '120' });
      out.textContent = (res.output || '').trim() || 'vm.log is empty — the VM has not booted yet';
      out.scrollTop = out.scrollHeight;
    } catch (e) {
      out.textContent = e.message;
    } finally {
      btn.disabled = false;
    }
  });

  // Wiping the overlay asks for a second tap, like the runtime reset.
  const wipe = $('pod-wipe');
  let wipeArmed = false;
  wipe.onclick = async () => {
    if (wipe.disabled) return;
    if (!wipeArmed) {
      wipeArmed = true;
      wipe.textContent = 'Tap again to wipe';
      setTimeout(() => {
        wipeArmed = false;
        wipe.textContent = 'Wipe storage overlay';
      }, 5000);
      return;
    }
    wipeArmed = false;
    wipe.textContent = 'Wiping…';
    try {
      await api('/api/podroid/wipe', { confirm: '1' });
      podNote('overlay wiped — boot the VM to format a fresh one');
    } catch (e) {
      podNote(e.message);
    } finally {
      wipe.textContent = 'Wipe storage overlay';
      fwdSig = '';
      await refresh();
    }
  };

  /* ------------------------------------------------------------ sections */

  const TABS = ['sites', 'images', 'packages', 'podroid', 'system', 'guide'];

  function showTab(name) {
    if (!TABS.includes(name)) name = 'sites';
    TABS.forEach((t) => {
      const panel = document.querySelector(`.tabpanel[data-tab="${t}"]`);
      if (panel) panel.classList.toggle('hidden', t !== name);
      const btn = document.querySelector(`#tabs button[data-tab="${t}"]`);
      if (btn) btn.setAttribute('aria-pressed', String(t === name));
    });
    try {
      localStorage.setItem('ps.tab', name);
    } catch (_) {}
  }

  document.querySelectorAll('#tabs button').forEach((btn) => {
    btn.onclick = () => showTab(btn.dataset.tab);
  });
  showTab((() => {
    try {
      return localStorage.getItem('ps.tab') || 'sites';
    } catch (_) {
      return 'sites';
    }
  })());

  // Live console over SSE.
  const consoleEl = $('console');
  let source;
  try {
    source = new EventSource(`/api/stream?token=${encodeURIComponent(token)}`);
    source.onmessage = (ev) => {
      try {
        const { line } = JSON.parse(ev.data);
        appendLine(line);
      } catch (_) {}
    };
    source.addEventListener('state', (ev) => {
      try {
        render(JSON.parse(ev.data));
      } catch (_) {}
    });
    source.onerror = () => {
      $('live').textContent = 'reconnecting';
    };
    source.onopen = () => {
      $('live').textContent = 'live';
    };
  } catch (_) {}

  function appendLine(line) {
    const atBottom = consoleEl.scrollHeight - consoleEl.scrollTop - consoleEl.clientHeight < 40;
    const span = document.createElement('div');
    span.className = line.includes('ERR') ? 'err' : '';
    span.textContent = line;
    consoleEl.appendChild(span);
    while (consoleEl.childElementCount > 500) consoleEl.removeChild(consoleEl.firstChild);
    if (atBottom) consoleEl.scrollTop = consoleEl.scrollHeight;
  }

  loadCatalog();
  refresh();
  setInterval(refresh, 5000);
})();
