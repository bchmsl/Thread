/* Thread v2 prototype: local SVG icon set (no network, no dependencies).
   Line icons are 24x24 strokes. Status glyphs are 16x16 fills.
   Every icon is decorative (aria-hidden); the control around it carries the name. */
(function () {
  'use strict';
  var T = (window.Thread = window.Thread || {});

  var LINE = {
    calendar: '<rect x="3.5" y="5" width="17" height="15" rx="2.5"/><path d="M3.5 10h17M8 3v4M16 3v4"/>',
    inbox: '<path d="M3.5 13.5 6 5.5h12l2.5 8V18a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18z"/><path d="M3.5 13.5H8l1.5 2.5h5l1.5-2.5h4.5"/>',
    folder: '<path d="M3.5 7V18a1.5 1.5 0 0 0 1.5 1.5h14a1.5 1.5 0 0 0 1.5-1.5V9.5A1.5 1.5 0 0 0 19 8h-7.2l-2-3H5A1.5 1.5 0 0 0 3.5 6.5z"/>',
    note: '<path d="M6 3.5h8l4.5 4.5v11a1.5 1.5 0 0 1-1.5 1.5H6A1.5 1.5 0 0 1 4.5 19V5A1.5 1.5 0 0 1 6 3.5z"/><path d="M14 3.5V8h4.5M8 12.5h8M8 16h5"/>',
    ticket: '<path d="M4 6h16v3.3a2.7 2.7 0 0 0 0 5.4V18H4v-3.3a2.7 2.7 0 0 0 0-5.4z"/><path d="M14.5 7.5v1.5M14.5 11.25v1.5M14.5 15v1.5"/>',
    pr: '<circle cx="6" cy="6" r="2.2"/><circle cx="6" cy="18" r="2.2"/><circle cx="18" cy="18" r="2.2"/><path d="M6 8.2v7.6M18 15.8V10a3 3 0 0 0-3-3h-4.5"/><path d="m12.5 4.5-2.5 2.5 2.5 2.5"/>',
    search: '<circle cx="11" cy="11" r="6.5"/><path d="m20 20-4.2-4.2"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    timer: '<circle cx="12" cy="13.5" r="7"/><path d="M12 10v3.5l2.5 1.5M9.5 3h5"/>',
    sliders: '<path d="M4 7h10M18 7h2M4 17h4M12 17h8"/><circle cx="16" cy="7" r="2"/><circle cx="10" cy="17" r="2"/>',
    trash: '<path d="M4.5 7h15M9.5 7V4.5h5V7M6.5 7l.8 12a1.5 1.5 0 0 0 1.5 1.4h6.4a1.5 1.5 0 0 0 1.5-1.4l.8-12"/><path d="M10 11v6M14 11v6"/>',
    chevronDown: '<path d="m6 9 6 6 6-6"/>',
    chevronRight: '<path d="m9 6 6 6-6 6"/>',
    chevronLeft: '<path d="m15 6-6 6 6 6"/>',
    x: '<path d="M6 6l12 12M18 6 6 18"/>',
    check: '<path d="m5 12.5 4.5 4.5L19 7.5"/>',
    bell: '<path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 2h-15z"/><path d="M10 20.5a2 2 0 0 0 4 0"/>',
    clock: '<circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/>',
    link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
    unlink: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/><path d="M4 4l16 16"/>',
    external: '<path d="M14 4.5h5.5V10M19.5 4.5 11 13"/><path d="M17 14v4.5a1.5 1.5 0 0 1-1.5 1.5h-10A1.5 1.5 0 0 1 4 18.5v-10A1.5 1.5 0 0 1 5.5 7H10"/>',
    alert: '<path d="M12 4 21 19.5H3z"/><path d="M12 10v4.5M12 17.3v.2"/>',
    cloud: '<path d="M7 18.5h10a4 4 0 0 0 .6-8 5.5 5.5 0 0 0-10.7 1A3.5 3.5 0 0 0 7 18.5z"/>',
    cloudOff: '<path d="M7 18.5h10a4 4 0 0 0 1.9-.5M20.9 15A4 4 0 0 0 17.6 10.5a5.5 5.5 0 0 0-8.4-3.8M6.6 8.6a5.5 5.5 0 0 0-.3 2.9A3.5 3.5 0 0 0 7 18.5"/><path d="M4 4l16 16"/>',
    laptop: '<rect x="5" y="5" width="14" height="10" rx="1.5"/><path d="M3 18.5h18"/>',
    refresh: '<path d="M19.5 12a7.5 7.5 0 1 1-2.2-5.3L19.5 9"/><path d="M19.5 4.5V9H15"/>',
    lock: '<rect x="5" y="10.5" width="14" height="9.5" rx="2"/><path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5"/>',
    play: '<path d="M8 5.5v13l10-6.5z"/>',
    pause: '<path d="M8.5 5.5v13M15.5 5.5v13"/>',
    stop: '<rect x="6.5" y="6.5" width="11" height="11" rx="1.5"/>',
    arrowUp: '<path d="M12 19V5M6 11l6-6 6 6"/>',
    arrowDown: '<path d="M12 5v14M6 13l6 6 6-6"/>',
    more: '<circle cx="5.5" cy="12" r="1.2"/><circle cx="12" cy="12" r="1.2"/><circle cx="18.5" cy="12" r="1.2"/>',
    bold: '<path d="M7.5 5h5.5a3.5 3.5 0 0 1 0 7H7.5zM7.5 12h6.5a3.5 3.5 0 0 1 0 7H7.5z"/>',
    italic: '<path d="M10.5 5h8M5.5 19h8M14.5 5l-5 14"/>',
    heading: '<path d="M5.5 5v14M14.5 5v14M5.5 12h9M18 13.5l2-1.5v7"/>',
    list: '<path d="M9.5 7h11M9.5 12h11M9.5 17h11"/><circle cx="5" cy="7" r=".9"/><circle cx="5" cy="12" r=".9"/><circle cx="5" cy="17" r=".9"/>',
    checklist: '<path d="m3.5 7 1.5 1.5L8 5.5M3.5 16l1.5 1.5L8 14.5M11 7h9.5M11 16h9.5"/>',
    user: '<circle cx="12" cy="8.5" r="3.5"/><path d="M5 20a7 7 0 0 1 14 0"/>',
    comment: '<path d="M5 5.5h14a1.5 1.5 0 0 1 1.5 1.5v8.5A1.5 1.5 0 0 1 19 17h-7l-4.5 3.5V17H5a1.5 1.5 0 0 1-1.5-1.5V7A1.5 1.5 0 0 1 5 5.5z"/>',
    branch: '<circle cx="6.5" cy="5.5" r="2"/><circle cx="6.5" cy="18.5" r="2"/><circle cx="17.5" cy="7.5" r="2"/><path d="M6.5 7.5v9M17.5 9.5a6 6 0 0 1-6 6h-5"/>',
    flask: '<path d="M9.5 3.5h5M10.5 3.5v5.5L5 18.5a1.4 1.4 0 0 0 1.2 2h11.6a1.4 1.4 0 0 0 1.2-2L13.5 9V3.5"/><path d="M7.5 14.5h9"/>',
    undo: '<path d="M9 14 4.5 9.5 9 5"/><path d="M4.5 9.5H15a5 5 0 0 1 0 10h-3"/>',
    signOut: '<path d="M14 4.5h4.5A1.5 1.5 0 0 1 20 6v12a1.5 1.5 0 0 1-1.5 1.5H14M10 16.5 14.5 12 10 7.5M14.5 12H4"/>',
    eye: '<path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z"/><circle cx="12" cy="12" r="2.5"/>',
    wifiOff: '<path d="M3.5 3.5l17 17M8.6 15.9a5 5 0 0 1 6.1-.6M5.2 12.4a10 10 0 0 1 3.4-2.2M13.6 9.6a10 10 0 0 1 5.2 2.8M2 8.9a15 15 0 0 1 3.4-2.3M11 4.6a15 15 0 0 1 11 4.3M12 19.5h.01"/>',
    edit: '<path d="M14.5 5.5l4 4L8.5 19.5h-4v-4z"/><path d="M12.5 7.5l4 4"/>',
    circleCheck: '<circle cx="12" cy="12" r="8.5"/><path d="m8.5 12.3 2.4 2.4 4.6-5"/>',
    circleX: '<circle cx="12" cy="12" r="8.5"/><path d="m9 9 6 6M15 9l-6 6"/>',
    circleDot: '<circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="2.5"/>',
    grip: '<circle cx="9" cy="7" r=".9"/><circle cx="15" cy="7" r=".9"/><circle cx="9" cy="12" r=".9"/><circle cx="15" cy="12" r=".9"/><circle cx="9" cy="17" r=".9"/><circle cx="15" cy="17" r=".9"/>',
    flag: '<path d="M5.5 20.5V4.5M5.5 5h11l-2 4 2 4h-11"/>',
    copy: '<rect x="8.5" y="8.5" width="11" height="11" rx="2"/><path d="M15.5 8.5V6A1.5 1.5 0 0 0 14 4.5H6A1.5 1.5 0 0 0 4.5 6v8A1.5 1.5 0 0 0 6 15.5h2.5"/>',
    layoutList: '<path d="M4 6.5h16M4 12h16M4 17.5h16"/>',
    layoutBoard: '<rect x="3.5" y="4.5" width="5" height="15" rx="1.5"/><rect x="9.5" y="4.5" width="5" height="10" rx="1.5"/><rect x="15.5" y="4.5" width="5" height="12.5" rx="1.5"/>',
    keyboard: '<rect x="3" y="6.5" width="18" height="11" rx="2"/><path d="M7 10h.01M10.3 10h.01M13.6 10h.01M17 10h.01M8 14h8"/>'
  };

  /* Status category glyphs (filled, 16x16). Category, never the display name, picks the glyph. */
  var GLYPH = {
    planned: '<rect x="3" y="3.5" width="10" height="2.2" rx="1.1"/><rect x="3" y="7" width="10" height="2.2" rx="1.1"/><rect x="3" y="10.5" width="6" height="2.2" rx="1.1"/>',
    active: '<path d="M5 3.2v9.6c0 .5.5.8.9.5l7.2-4.8a.6.6 0 0 0 0-1L5.9 2.7c-.4-.3-.9 0-.9.5z"/>',
    paused: '<rect x="3.8" y="3.2" width="3" height="9.6" rx="1.1"/><rect x="9.2" y="3.2" width="3" height="9.6" rx="1.1"/>',
    done: '<path d="m3.4 8.6 3 3 6.2-7.2" fill="none" stroke="currentColor" stroke-width="2.3" stroke-linecap="round" stroke-linejoin="round"/>'
  };

  T.icon = function (name, cls) {
    var body = LINE[name] || LINE.more;
    return '<svg class="ic' + (cls ? ' ' + cls : '') + '" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false">' + body + '</svg>';
  };

  T.glyph = function (cat) {
    return '<svg class="glyph" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false">' + (GLYPH[cat] || GLYPH.planned) + '</svg>';
  };
})();
