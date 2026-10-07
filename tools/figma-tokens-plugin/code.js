// Konstruct tokens — a local Figma plugin for the owner's design file.
//
// build-screens: composes the app's screens (dark theme) from the components on the "Components"
//   page, bound to the CTColor · Dark / CTSpace / CTLayout / CTRadius variables and CTFont styles.
//   Rebuilds the "Screens · Dark" section on the "Current" page from scratch each run.
// export-tokens: shows every variable and CTFont style as JSON, the input for updating the code
//   (Android ui/theme, iOS ConstructTheme.swift). Names are the code's names.
//
// Runs on any plan: it is a development plugin, not the MCP connector. Import it in Figma Desktop:
// Plugins → Development → Import plugin from manifest… → this folder's manifest.json.

const SCREENS = 'Screens · Dark';
const DARK = 'CTColor · Dark';

// ── Lookup ────────────────────────────────────────────────────────────────────

async function loadVariables() {
  const V = {};
  for (const c of await figma.variables.getLocalVariableCollectionsAsync()) {
    for (const id of c.variableIds) {
      const v = await figma.variables.getVariableByIdAsync(id);
      V[`${c.name}/${v.name}`] = v;
    }
  }
  return V;
}

async function loadComponents() {
  const page = figma.root.children.find(p => p.name === 'Components');
  if (!page) throw new Error('No "Components" page');
  await page.loadAsync();
  const K = {};
  for (const n of page.findAllWithCriteria({ types: ['COMPONENT_SET', 'COMPONENT'] })) {
    if (n.type === 'COMPONENT' && n.parent && n.parent.type === 'COMPONENT_SET') continue;
    K[n.name] = n;
  }
  for (const name of ['Icon', 'CTButton', 'SectionHeader', 'CTSettingsRow', 'CTNavBar', 'ChatNavBar', 'CTSearchBar', 'CTTabBar', 'MessageBubble', 'MessageInput', 'Avatar', 'ChatRow', 'CallRow']) {
    if (!K[name]) throw new Error(`No component "${name}" on the Components page`);
  }
  return K;
}

// ── Build ─────────────────────────────────────────────────────────────────────

async function buildScreens() {
  for (const style of ['Regular', 'Bold']) await figma.loadFontAsync({ family: 'JetBrains Mono', style });
  await figma.loadFontAsync({ family: 'Material Icons', style: 'Regular' });
  await figma.loadFontAsync({ family: 'Inter', style: 'Regular' });

  const V = await loadVariables();
  const K = await loadComponents();
  const styles = {};
  for (const s of await figma.getLocalTextStylesAsync()) styles[s.name] = s;

  const page = figma.root.children.find(p => p.name === 'Current');
  await figma.setCurrentPageAsync(page);
  const old = page.children.find(n => n.name === SCREENS);
  if (old) old.remove();

  const C = name => {
    const v = V[`${DARK}/${name}`];
    if (!v) throw new Error(`No colour ${name}`);
    return v;
  };
  const paint = (name, opacity = 1) =>
    figma.variables.setBoundVariableForPaint({ type: 'SOLID', color: { r: 0, g: 0, b: 0 }, opacity }, 'color', C(name));
  const fill = (node, name, opacity = 1) => { node.fills = [paint(name, opacity)]; };
  const bindPad = (f, sides, name) => { for (const s of sides) f.setBoundVariable(s, V[name]); };
  const radius = (f, name) => { for (const k of ['topLeftRadius', 'topRightRadius', 'bottomLeftRadius', 'bottomRightRadius']) f.setBoundVariable(k, V[name]); };

  const frame = (dir, name, o = {}) => {
    const f = figma.createFrame();
    f.name = name;
    f.layoutMode = dir;
    f.primaryAxisSizingMode = 'AUTO';
    f.counterAxisSizingMode = 'AUTO';
    f.fills = [];
    for (const k of ['itemSpacing', 'paddingLeft', 'paddingRight', 'paddingTop', 'paddingBottom', 'primaryAxisAlignItems', 'counterAxisAlignItems', 'cornerRadius']) {
      if (o[k] !== undefined) f[k] = o[k];
    }
    return f;
  };
  const text = async (chars, style, color) => {
    const t = figma.createText();
    await t.setTextStyleIdAsync(styles[style].id);
    t.characters = chars;
    fill(t, color);
    return t;
  };
  const propKeys = c => {
    const owner = c;
    const defs = owner.componentPropertyDefinitions || {};
    const map = {};
    for (const k of Object.keys(defs)) map[k.split('#')[0]] = k;
    return map;
  };
  const inst = (name, set = {}, variant) => {
    const c = K[name];
    const comp = c.type === 'COMPONENT_SET' ? (variant ? c.children.find(v => v.name === variant) : c.defaultVariant) : c;
    const i = comp.createInstance();
    const keys = propKeys(c);
    const map = {};
    for (const [n, v] of Object.entries(set)) map[keys[n]] = v;
    if (Object.keys(map).length) i.setProperties(map);
    return i;
  };
  const glyphOf = i => i.findAll(n => n.type === 'TEXT' && n.fontName.family === 'Material Icons')[0];
  const glyphKey = Object.keys(K.Icon.componentPropertyDefinitions)[0];
  // The glyph is the Icon component's text property: set it on the nested Icon instance, not the text.
  const setGlyph = (container, g, color) => {
    const ic = container.findAll(n => n.type === 'INSTANCE' && n.name === 'Icon')[0];
    ic.setProperties({ [glyphKey]: g });
    if (color) fill(glyphOf(ic), color);
  };
  const labelOf = i => i.findAll(n => n.type === 'TEXT' && n.fontName.family === 'JetBrains Mono')[0];
  const icon = (g, size, color) => {
    const i = inst('Icon', { Glyph: g });
    i.resize(size, size);
    const t = glyphOf(i);
    t.fontSize = size;
    fill(t, color);
    return i;
  };
  const add = (parent, n) => { parent.appendChild(n); n.layoutSizingHorizontal = 'FILL'; return n; };
  const spacer = parent => {
    const s = figma.createFrame();
    s.name = 'spacer'; s.fills = []; s.resize(10, 10);
    parent.appendChild(s);
    s.layoutSizingHorizontal = 'FILL';
    s.layoutGrow = 1;
  };
  const inset = (parent, n) => {
    const w = frame('VERTICAL', n.name, { paddingTop: 4, paddingBottom: 4 });
    bindPad(w, ['paddingLeft', 'paddingRight'], 'CTLayout/edgePad');
    add(parent, w);
    add(w, n);
    return w;
  };
  const toggle = on => {
    const t = frame('HORIZONTAL', on ? 'switch on' : 'switch off', { paddingLeft: 3, paddingRight: 3, counterAxisAlignItems: 'CENTER', primaryAxisAlignItems: on ? 'MAX' : 'MIN' });
    t.primaryAxisSizingMode = 'FIXED'; t.counterAxisSizingMode = 'FIXED';
    t.resize(44, 26); t.cornerRadius = 13;
    fill(t, on ? 'accent' : 'disabledBg');
    const knob = figma.createEllipse();
    knob.resize(20, 20);
    fill(knob, on ? 'onFill' : 'textDim');
    t.appendChild(knob);
    return t;
  };

  const section = figma.createSection();
  section.name = SCREENS;
  page.appendChild(section);
  section.x = 1600; section.y = 100;
  let col = 0;
  const screen = name => {
    const f = frame('VERTICAL', name, { paddingTop: 47, paddingBottom: 24 });
    f.primaryAxisSizingMode = 'FIXED'; f.counterAxisSizingMode = 'FIXED';
    f.resize(390, 844);
    f.clipsContent = true;
    fill(f, 'bg');
    section.appendChild(f);
    f.x = 80 + (col % 5) * 440;
    f.y = 80 + Math.floor(col / 5) * 940;
    col++;
    return f;
  };
  const rootHeader = (f, title, trailing) => {
    const nav = inst('CTNavBar', { Title: title }, 'Type=Root');
    add(f, nav);
    if (!trailing) {
      const g = nav.findAll(n => n.type === 'INSTANCE')[0];
      if (g) g.visible = false;
    }
    return nav;
  };
  const subHeader = (f, title) => add(f, inst('CTNavBar', { Title: title }, 'Type=Sub'));
  const header = (f, title) => add(f, inst('SectionHeader', { Title: title }));
  const row = (parent, label, value = '', o = {}) => {
    const r = inst('CTSettingsRow', { Label: label, Value: value, 'Show icon': !!o.icon, 'Show chevron': !!o.chevron });
    if (o.icon) setGlyph(r, o.icon);
    if (o.labelColor) fill(labelOf(r), o.labelColor);
    if (o.valueColor) { const texts = r.findAll(n => n.type === 'TEXT' && n.fontName.family === 'JetBrains Mono'); fill(texts[1], o.valueColor); }
    if (o.flat) fill(r, 'bg');
    add(parent, r);
    return r;
  };
  const card = parent => {
    const outer = frame('VERTICAL', 'card', { paddingTop: 4, paddingBottom: 4 });
    bindPad(outer, ['paddingLeft', 'paddingRight'], 'CTLayout/edgePad');
    add(parent, outer);
    const c = frame('VERTICAL', 'group');
    fill(c, 'bgMsg');
    radius(c, 'CTRadius/card');
    c.clipsContent = true;
    add(outer, c);
    return c;
  };
  const caption = async (parent, s) => {
    const w = frame('VERTICAL', 'footer', { paddingTop: 4, paddingBottom: 8 });
    bindPad(w, ['paddingLeft', 'paddingRight'], 'CTSpace/l');
    add(parent, w);
    const t = await text(s, 'CTFont/caption', 'textDim');
    w.appendChild(t);
    t.layoutSizingHorizontal = 'FILL';
    t.textAutoResize = 'HEIGHT';
  };
  const option = async (parent, label, checked, glyph) => {
    const r = frame('HORIZONTAL', label, { itemSpacing: 12, counterAxisAlignItems: 'CENTER', paddingTop: 12, paddingBottom: 12 });
    bindPad(r, ['paddingLeft', 'paddingRight'], 'CTSpace/l');
    add(parent, r);
    if (glyph) r.appendChild(icon(glyph, 20, 'textDim'));
    const t = await text(label, 'CTFont/ui/rowLarge', 'text');
    r.appendChild(t);
    t.layoutGrow = 1;
    if (checked) r.appendChild(icon('check', 20, 'accent'));
  };
  const segmented = (parent, items, selected) => {
    const s = frame('HORIZONTAL', 'segmented', { paddingLeft: 2, paddingRight: 2, paddingTop: 2, paddingBottom: 2, cornerRadius: 6 });
    s.strokes = [paint('noise')]; s.strokeWeight = 1;
    parent.appendChild(s);
    return Promise.all(items.map(async (label, i) => {
      const seg = frame('HORIZONTAL', label, { paddingLeft: 20, paddingRight: 20, paddingTop: 4, paddingBottom: 4, primaryAxisAlignItems: 'CENTER', cornerRadius: 4 });
      if (i === selected) fill(seg, 'accent');
      seg.appendChild(await text(label, 'CTFont/caption', i === selected ? 'bg' : 'textDim'));
      s.appendChild(seg);
    }));
  };

  // 1. Chats
  {
    const f = screen('Chats');
    const head = frame('HORIZONTAL', 'header', { primaryAxisAlignItems: 'SPACE_BETWEEN', counterAxisAlignItems: 'CENTER' });
    bindPad(head, ['paddingLeft', 'paddingRight'], 'CTLayout/edgePad');
    head.counterAxisSizingMode = 'FIXED'; head.resize(390, 44);
    const dot = figma.createEllipse(); dot.resize(8, 8); fill(dot, 'online');
    head.appendChild(dot);
    head.appendChild(icon('qr_code_scanner', 24, 'accent'));
    add(f, head);
    inset(f, inst('CTSearchBar'));
    add(f, inst('ChatRow', { Name: 'Alice', Time: '2:17 PM', Preview: 'Checking it side by side right now', 'Show unread': true }));
    add(f, inst('ChatRow', { Name: 'Bob', Time: 'Yesterday', Preview: 'Sure, 10am', 'Show unread': false }));
    spacer(f);
    inset(f, inst('CTTabBar'));
  }

  // 2. Chat
  {
    const f = screen('Chat — Alice');
    inset(f, inst('ChatNavBar', { Name: 'Alice' }));
    const list = frame('VERTICAL', 'messages', { itemSpacing: 6, paddingTop: 12 });
    bindPad(list, ['paddingLeft', 'paddingRight'], 'CTSpace/m');
    add(f, list);
    const msgs = [
      ['In', 'Hi! Did the new build reach you?', '13:19'],
      ['Out', 'Yes, installed it this morning', null],
      ['Out', 'The palette looks much better now', '13:21'],
      ['In', 'Great. Typography should match iOS too', '14:09'],
      ['Out', 'Checking it side by side right now', '14:17'],
    ];
    for (const [d, t, time] of msgs) {
      const wrap = frame('VERTICAL', `${d} message`, { itemSpacing: 2, counterAxisAlignItems: d === 'Out' ? 'MAX' : 'MIN' });
      add(list, wrap);
      wrap.appendChild(inst('MessageBubble', { Text: t }, `Direction=${d}`));
      if (time) {
        const meta = frame('HORIZONTAL', 'meta', { itemSpacing: 4, counterAxisAlignItems: 'CENTER' });
        if (d === 'Out') meta.appendChild(icon('check_circle', 12, 'delivered'));
        meta.appendChild(await text(time, 'CTFont/micro', 'textDim'));
        wrap.appendChild(meta);
      }
    }
    spacer(f);
    add(f, inst('MessageInput'));
  }

  // 3. Contact profile
  {
    const f = screen('Contact profile — Alice');
    subHeader(f, 'Profile');
    const box = frame('VERTICAL', 'avatar', { counterAxisAlignItems: 'CENTER', paddingTop: 16, paddingBottom: 16 });
    const big = inst('Avatar'); big.rescale(2.4); box.appendChild(big);
    add(f, box);
    header(f, 'IDENTITY');
    for (const [l, v] of [['username', '<@alice>'], ['display name', 'Alice'], ['local name', 'Not set'], ['fingerprint', 'Not pinned yet'], ['user id', 'aaaaaaaa...aa']]) row(f, l, v, { flat: true });
    header(f, 'ACTIONS');
    for (const l of ['voice call', 'video call', 'share my profile']) row(f, l, '', { flat: true, chevron: true, labelColor: 'accent' });
    header(f, 'SECURITY');
    row(f, 'encryption', 'No active session', { flat: true });
    row(f, 'safety numbers', '', { flat: true, chevron: true });
  }

  // 4. Synaps
  {
    const f = screen('Synaps');
    rootHeader(f, 'Synapses', true);
    inset(f, inst('CTSearchBar'));
    spacer(f);
    const grid = frame('HORIZONTAL', 'graph', { itemSpacing: 40, primaryAxisAlignItems: 'CENTER' });
    add(f, grid);
    for (const name of ['Alice', 'Bob']) {
      const node = frame('VERTICAL', name, { itemSpacing: 6, counterAxisAlignItems: 'CENTER' });
      const a = inst('Avatar'); a.rescale(1.5); node.appendChild(a);
      node.appendChild(await text(name, 'CTFont/caption', 'text'));
      grid.appendChild(node);
    }
    spacer(f);
    inset(f, inst('CTTabBar'));
  }

  // 5. Calls
  {
    const f = screen('Calls');
    rootHeader(f, 'Recents', false);
    const segRow = frame('HORIZONTAL', 'filter', { paddingTop: 4, paddingBottom: 8 });
    bindPad(segRow, ['paddingLeft', 'paddingRight'], 'CTLayout/edgePad');
    add(f, segRow);
    await segmented(segRow, ['All', 'Missed'], 0);
    const calls = [
      ['Alice', 'Incoming', '2h ago', '4:12', 'call_received', 'accent'],
      ['Bob', 'Outgoing', '1d ago', '1:04', 'call_made', 'textDim'],
      ['Alice', 'Missed', '2d ago', '', 'call_received', 'danger'],
    ];
    for (const [n, s, w, d, g, c] of calls) {
      const r = inst('CallRow', { Name: n, Status: s, When: w, Duration: d });
      setGlyph(r, g, c);
      if (c === 'danger') fill(labelOf(r), 'danger');
      add(f, r);
    }
    spacer(f);
    inset(f, inst('CTTabBar'));
  }

  // 6. Settings
  {
    const f = screen('Settings');
    rootHeader(f, 'Settings', false);
    const me = card(f);
    const prof = frame('HORIZONTAL', 'profile', { itemSpacing: 12, counterAxisAlignItems: 'CENTER', paddingTop: 12, paddingBottom: 12 });
    bindPad(prof, ['paddingLeft', 'paddingRight'], 'CTSpace/m');
    add(me, prof);
    const a = inst('Avatar'); a.rescale(1.4); prof.appendChild(a);
    const who = frame('VERTICAL', 'who', { itemSpacing: 2 });
    prof.appendChild(who); who.layoutGrow = 1;
    const nm = await text('STALE MONGOOSE', 'CTFont/headline', 'text'); who.appendChild(nm);
    who.appendChild(await text('no username', 'CTFont/secondary', 'textDim'));
    who.appendChild(await text('○ not searchable', 'CTFont/caption', 'textDim'));
    prof.appendChild(icon('chevron_right', 20, 'accent'));
    row(card(f), 'INVITE', '', { icon: 'qr_code', chevron: true });
    const g = card(f);
    for (const [l, i] of [['LINKED DEVICES', 'laptop'], ['APPEARANCE', 'brush'], ['SECURITY', 'lock'], ['NOTIFICATIONS', 'notifications'], ['NETWORK', 'public'], ['DRAFTS', 'folder']]) row(g, l, '', { icon: i, chevron: true });
    const h = card(f);
    row(h, 'HOW KONSTRUCT WORKS', '', { icon: 'menu_book', chevron: true });
    row(h, 'VERSION', 'v0.17.5 BETA', { icon: 'info', valueColor: 'warning' });
    row(card(f), 'DIAGNOSTICS & LOGS', '', { chevron: true, labelColor: 'warning' });
    spacer(f);
    inset(f, inst('CTTabBar'));
  }

  // 7. Account
  {
    const f = screen('Account — Identity');
    subHeader(f, 'Identity');
    const box = frame('VERTICAL', 'avatar', { itemSpacing: 8, counterAxisAlignItems: 'CENTER', paddingTop: 16, paddingBottom: 16 });
    const big = inst('Avatar'); big.rescale(2.4); box.appendChild(big);
    box.appendChild(await text('[change photo]', 'CTFont/body', 'accent'));
    add(f, box);
    header(f, 'IDENTITY');
    row(f, 'username', 'no username', { flat: true });
    await caption(f, 'your unique public identifier. tap to change.');
    row(f, 'display name', 'stale mongoose', { flat: true, valueColor: 'text' });
    row(f, 'fingerprint', '8086 F3E4 4AAC AE7B', { flat: true, valueColor: 'accent' });
    header(f, 'ACCOUNT');
    row(f, 'user id', '4ac76d25...8b', { flat: true });
    row(f, 'linked devices', '[manage]', { flat: true, valueColor: 'accent' });
    row(f, 'sign out', '', { flat: true, chevron: true });
    header(f, 'DANGER ZONE');
    row(f, 'sign out all devices', '', { flat: true, chevron: true, labelColor: 'danger' });
    row(f, 'delete account', '[delete]', { flat: true, labelColor: 'danger', valueColor: 'danger' });
  }

  // 8. Appearance
  {
    const f = screen('Appearance');
    subHeader(f, 'Appearance');
    header(f, 'THEME');
    let c = card(f);
    await option(c, 'Automatic', false, 'contrast');
    await option(c, 'Light', false, 'light_mode');
    await option(c, 'Dark', true, 'dark_mode');
    await caption(f, 'Choose how Konstruct looks. Automatic adjusts based on your system settings.');
    header(f, 'MESSAGE FONT');
    c = card(f);
    await option(c, 'System', true);
    await option(c, 'JetBrains Mono', false);
    await caption(f, 'Applies to message text and the composer. The rest of the interface stays monospaced.');
    header(f, 'TEXT SIZE');
    c = card(f);
    await option(c, 'Compact', false);
    await option(c, 'Standard', true);
    await option(c, 'Large', false);
  }

  // 9. Security
  {
    const f = screen('Security');
    subHeader(f, 'Security');
    row(f, 'Enable PIN Code', '', { flat: true, chevron: true });
    row(f, 'Identity Recovery (Seed Phrase)', '', { flat: true, chevron: true });
    await caption(f, 'Restore access using your recovery seed phrase.');
    const lock = row(f, 'Lockdown Mode', '', { flat: true, icon: 'lock' });
    await caption(f, 'Only contacts added before activation receive notifications. New senders are silently saved — you can review them anytime.');
    row(f, 'sender anonymity', '', { flat: true, icon: 'visibility_off' });
    await caption(f, 'Konstruct never learns who sent a message. Always on — there is no switch, and nothing to keep topped up.');
    row(f, 'ISSUED INVITES', '', { flat: true, icon: 'badge', chevron: true });
    header(f, 'KEY TRANSPARENCY');
    row(f, 'merkle log', 'no data', { flat: true, icon: 'tag' });
    row(f, 'searchable by username', '', { flat: true, icon: 'visibility_off' });
    await caption(f, 'set a username first to enable search');
    const sw = frame('HORIZONTAL', 'switches note', { paddingTop: 8 });
    bindPad(sw, ['paddingLeft', 'paddingRight'], 'CTSpace/l');
    sw.itemSpacing = 12;
    add(f, sw);
    sw.appendChild(toggle(false));
    sw.appendChild(toggle(true));
    lock.name = 'Lockdown Mode (switch: see switches note)';
  }

  // 10. Network
  {
    const f = screen('Network');
    subHeader(f, 'Network');
    header(f, 'STATUS');
    const st = card(f);
    const conn = frame('HORIZONTAL', 'connected', { itemSpacing: 12, counterAxisAlignItems: 'CENTER', paddingTop: 12, paddingBottom: 12 });
    bindPad(conn, ['paddingLeft', 'paddingRight'], 'CTSpace/m');
    add(st, conn);
    conn.appendChild(icon('check_circle', 24, 'online'));
    const cc = frame('VERTICAL', 'text', { itemSpacing: 2 });
    conn.appendChild(cc);
    cc.appendChild(await text('Connected', 'CTFont/body', 'text'));
    cc.appendChild(await text('TLS 1.3 ams.konstruct.cc:443', 'CTFont/caption', 'textDim'));
    row(st, 'Last Heartbeat', '10 sec');
    header(f, 'CENSORSHIP PROTECTION');
    const cp = card(f);
    const cpr = frame('HORIZONTAL', 'protection', { counterAxisAlignItems: 'CENTER', paddingTop: 10, paddingBottom: 10 });
    bindPad(cpr, ['paddingLeft', 'paddingRight'], 'CTSpace/m');
    add(cp, cpr);
    const cpl = await text('Censorship protection', 'CTFont/body', 'text');
    cpr.appendChild(cpl); cpl.layoutGrow = 1;
    await segmented(cpr, ['Off', 'Auto', 'On'], 1);
    await caption(f, 'Turns on automatically when blocking is detected. Setup happens in the background.');
  }

  figma.viewport.scrollAndZoomIntoView([section]);
  return section;
}

// ── Export ────────────────────────────────────────────────────────────────────

const hex = c => '#' + [c.r, c.g, c.b].map(x => Math.round(x * 255).toString(16).padStart(2, '0')).join('').toUpperCase();

async function exportTokens() {
  const out = { source: figma.root.name, exportedAt: new Date().toISOString(), variables: {}, textStyles: {} };
  for (const c of await figma.variables.getLocalVariableCollectionsAsync()) {
    const coll = {};
    for (const id of c.variableIds) {
      const v = await figma.variables.getVariableByIdAsync(id);
      const raw = v.valuesByMode[c.modes[0].modeId];
      let value = raw;
      if (v.resolvedType === 'COLOR' && raw && raw.r !== undefined) value = { hex: hex(raw), alpha: Math.round((raw.a === undefined ? 1 : raw.a) * 1000) / 1000 };
      if (raw && raw.type === 'VARIABLE_ALIAS') value = { alias: (await figma.variables.getVariableByIdAsync(raw.id)).name };
      coll[v.name] = { value, android: v.codeSyntax.ANDROID || null, ios: v.codeSyntax.iOS || null, description: v.description || '' };
    }
    out.variables[c.name] = coll;
  }
  for (const s of await figma.getLocalTextStylesAsync()) {
    if (!s.name.startsWith('CTFont/')) continue;
    out.textStyles[s.name] = {
      family: s.fontName.family,
      style: s.fontName.style,
      size: s.fontSize,
      letterSpacing: s.letterSpacing,
      description: s.description || '',
    };
  }
  return JSON.stringify(out, null, 2);
}

const exportUi = json => `
<style>body{font:12px monospace;margin:8px}textarea{width:100%;height:520px;font:11px monospace}</style>
<p>Copy all, save as <b>design-tokens.json</b> and hand it over.</p>
<textarea id="t" readonly></textarea>
<script>document.getElementById('t').value = ${JSON.stringify(json)}; document.getElementById('t').select();</script>`;

(async () => {
  try {
    if (figma.command === 'export-tokens') {
      const json = await exportTokens();
      figma.showUI(exportUi(json), { width: 560, height: 600 });
      figma.ui.onmessage = () => figma.closePlugin();
      return;
    }
    await buildScreens();
    figma.closePlugin('Screens built — section "Screens · Dark" on the Current page');
  } catch (e) {
    figma.closePlugin('Failed: ' + (e && e.message ? e.message : String(e)));
  }
})();
