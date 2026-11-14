(() => {
    'use strict';

    const { $, $$, escapeHtml, setContent, emptyState, formatMoment, formatRange, statusBadge, toast,
        createSession, sessionKeys, pendingMealKey } = window.Campus;
    const titles = { meals: '今日餐品', window: '取餐窗口', reservations: '我的预约', waitlist: '我的候补' };
    const waitlistLabels = {
        EXPIRED: '确认超时', LEFT: '已退出', CLOSED: '批次已截止', CONFIRMED: '已提交预约', FAILED: '预约未成功'
    };
    const offerTime = new Intl.DateTimeFormat('zh-CN', {
        timeZone: 'Asia/Shanghai', month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit'
    });
    const campusDate = new Intl.DateTimeFormat('zh-CN', {
        timeZone: 'Asia/Shanghai', month: 'long', day: 'numeric', weekday: 'long'
    });
    const calendarDate = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' });
    const demoMealPhotos = new Map([
        ['香菇鸡腿饭', '/meals/shiitake-chicken.jpg'],
        ['番茄牛腩饭', '/meals/tomato-beef.jpg'],
        ['青菜豆腐盖饭', '/meals/tofu-greens.jpg'],
        ['黑椒鸡排饭', '/meals/black-pepper-chicken.jpg']
    ]);
    const state = {
        page: 'meals',
        windowId: '',
        reservationId: '',
        reservationStatus: 'ALL',
        reservationPage: 1,
        user: null,
        menuRequest: null,
        menuTimer: null,
        waitlistAction: false,
        pageRequest: null,
        pageTimer: null
    };
    const { api, restoreAccount } = createSession(state, {
        storageKey: sessionKeys.student,
        accountPath: '/user/me',
        signIn,
        onChange() {
            if (!state.user) {
                ['#reservationList', '#reservationTicket', '#waitlistEntries'].forEach(selector => setContent($(selector), ''));
                state.reservationStatus = 'ALL';
                state.reservationPage = 1;
            }
            if (state.page !== 'meals' || !state.user) renderPage();
            if (!state.user) return;
            const pending = sessionStorage.getItem(pendingMealKey) || '';
            sessionStorage.removeItem(pendingMealKey);
            const queued = pending.match(/^waitlist:([1-9]\d{0,18})$/);
            if (state.page === 'waitlist' && queued) enqueueWaitlist(queued[1]);
            if (['meals', 'window'].includes(state.page) && /^[1-9]\d{0,18}$/.test(pending)) reserveMeal(pending);
        }
    });

    function signIn(batchId = '') {
        const params = new URLSearchParams();
        sessionStorage.removeItem(pendingMealKey);
        if (batchId) {
            params.set('meal', batchId);
            sessionStorage.setItem(pendingMealKey, `login:${batchId}`);
        }
        if (state.page !== 'meals') params.set('next', location.hash);
        location.assign(params.size ? `/login.html?${params}` : '/login.html');
    }

    function scheduleReload(load, signal, delay) {
        clearTimeout(state.pageTimer);
        state.pageTimer = setTimeout(() => {
            if (!signal.aborted) load(signal);
        }, delay);
    }

    function renderPage(focus = false) {
        if (/^#\/staff\/?$/.test(location.hash)) {
            location.replace('/backoffice.html');
            return;
        }
        state.pageRequest?.abort();
        state.menuRequest?.abort();
        clearTimeout(state.pageTimer);
        clearTimeout(state.menuTimer);
        state.pageRequest = new AbortController();
        const { signal } = state.pageRequest;
        const [, page, id] = location.hash.match(/^#\/(window|reservations|waitlist)(?:\/([1-9]\d{0,18}))?\/?$/) || [];
        state.page = page === 'window' && !id || page === 'waitlist' && id ? 'meals' : page || 'meals';
        state.windowId = state.page === 'window' ? id : '';
        const reservationId = state.page === 'reservations' ? id || '' : '';
        if (reservationId !== state.reservationId) $('#reservationTicket').hidden = true;
        state.reservationId = reservationId;
        $$('[data-page]').forEach(panel => { panel.hidden = panel.dataset.page !== state.page; });
        $$('[data-route]').forEach(link => {
            if (link.dataset.route === (state.page === 'window' ? 'meals' : state.page)) link.setAttribute('aria-current', 'page');
            else link.removeAttribute('aria-current');
        });
        document.title = `${titles[state.page]} · 校园取餐`;
        if (focus) {
            $(`[data-page="${state.page}"] h1`).focus({ preventScroll: true });
            window.scrollTo(0, 0);
        }
        if (state.page === 'meals') loadMeals(signal);
        if (state.page === 'window') loadWindow(state.windowId, signal);
        if (state.page === 'reservations') loadReservations(signal);
        if (state.page === 'waitlist') loadWaitlist(signal);
    }

    async function loadMeals(signal) {
        const now = new Date();
        const date = $('#mealDate');
        date.dateTime = calendarDate.format(now);
        date.textContent = campusDate.format(now);
        date.hidden = false;
        const overview = $('#mealWindowOverview');
        overview.setAttribute('aria-busy', 'true');
        const [menu, directory] = await Promise.allSettled([
            api('/meal-batches?today=true', { signal }),
            api('/campus-windows', { signal })
        ]);
        if (signal.aborted) return;
        overview.setAttribute('aria-busy', 'false');
        $('#mealFilters').hidden = false;
        if (directory.status === 'fulfilled') {
            const windows = directory.value;
            const select = $('#mealWindow');
            const selected = select.value;
            setContent(select, '<option value="">全部窗口</option>' + windows.map(window =>
                `<option value="${escapeHtml(window.id)}">${escapeHtml(window.name)}</option>`).join(''));
            select.value = windows.some(window => window.id === selected) ? selected : '';
        }
        if (menu.status === 'fulfilled') {
            const today = menu.value;
            const open = today.filter(isBookable);
            $('#openBatchCount').textContent = open.length;
            $('#openPortionCount').textContent = open.reduce((total, batch) => total + batch.available, 0);
            $('#upcomingBatchCount').textContent = today.filter(batch => !batch.bookingOpen).length;
            $('#mealSummary').hidden = false;

            renderWindowOverview(today);
            loadMenu(today);
        } else {
            $('#mealSummary').hidden = true;
            $('#allMealWindows').hidden = true;
            setContent(overview, '<p class="window-overview-message">暂时无法查询窗口余量。</p>');
            loadMenu();
        }
    }

    function isBookable(batch) {
        return batch.bookingOpen && batch.available > 0;
    }

    function renderWindowOverview(batches) {
        const windows = new Map();
        for (const batch of batches) {
            if (!windows.has(batch.windowId)) {
                windows.set(batch.windowId, { id: batch.windowId, name: batch.shopName, available: 0, opensAt: '' });
            }
            const window = windows.get(batch.windowId);
            if (isBookable(batch)) window.available += batch.available;
            if (!batch.bookingOpen && batch.available > 0 && (!window.opensAt || batch.beginTime < window.opensAt)) {
                window.opensAt = batch.beginTime;
            }
        }
        const overview = $('#mealWindowOverview');
        $('#allMealWindows').hidden = windows.size === 0;
        if (!windows.size) {
            setContent(overview, '<p class="window-overview-message">今日还没有窗口发布餐品。</p>');
            return;
        }
        const visible = [...windows.values()].sort((a, b) => b.available - a.available ||
            (a.opensAt || '9999').localeCompare(b.opensAt || '9999')).slice(0, 3);
        setContent(overview, `<ul class="window-overview-list">${visible.map(window => {
            let availability = '已约满';
            if (window.available) availability = `<strong>${window.available}</strong> 份`;
            else if (window.opensAt) availability = `${formatMoment(window.opensAt)} 开放`;
            return `<li><a class="window-shortcut" href="#/window/${escapeHtml(window.id)}">
                <span class="window-shortcut-name">${escapeHtml(window.name)}</span>
                <span class="window-shortcut-value${!window.available && window.opensAt ? ' pending' : ''}">${availability} <span aria-hidden="true">›</span></span>
            </a></li>`;
        }).join('')}</ul>`);
    }

    function jumpToMenu() {
        const heading = $('#mealBoardTitle');
        heading.focus({ preventScroll: true });
        heading.scrollIntoView({ behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
    }

    async function loadMenu(initial) {
        clearTimeout(state.menuTimer);
        state.menuRequest?.abort();
        state.menuRequest = new AbortController();
        const { signal } = state.menuRequest;
        const list = $('#mealBatches');
        const count = $('#mealResultCount');
        const params = new URLSearchParams({ today: 'true' });
        for (const [key, value] of new FormData($('#mealFilters'))) {
            if (value.trim()) params.set(key, value.trim());
        }
        const filtered = params.size > 1;
        const selectedFilters = ['windowId', 'status'].filter(key => params.has(key)).length;
        const filterCount = $('#mealFilterCount');
        filterCount.textContent = selectedFilters ? `(${selectedFilters})` : '';
        filterCount.hidden = !selectedFilters;
        $('#mealFilterResult').hidden = !filtered;
        count.textContent = '';
        list.setAttribute('aria-busy', 'true');
        try {
            const batches = !filtered && initial ? initial : await api(`/meal-batches?${params}`, { signal });
            if (signal.aborted) return;
            if (filtered) {
                count.textContent = batches.length === 50 ? '显示前 50 个批次，可继续缩小筛选范围。' : `共 ${batches.length} 个批次`;
            }
            const ordered = [...batches].sort((left, right) => Number(isBookable(right)) - Number(isBookable(left)));
            setContent(list, batches.length ? mealCards(ordered, true) :
                filtered ? emptyState('没有符合筛选条件的餐品', '可更换关键词、窗口或预约状态。') :
                    emptyState('今天暂无餐品', '食堂发布新批次后会显示在这里。'));
        } catch (error) {
            if (signal.aborted) return;
            setContent(list, emptyState('餐品加载失败', error.message));
        } finally {
            if (!signal.aborted) list.setAttribute('aria-busy', 'false');
        }
    }

    async function loadWindow(id, signal) {
        $('#windowTitle').textContent = '取餐窗口';
        $('#windowAddress').hidden = $('#windowStock').hidden = $('#windowSchedule').hidden = true;
        setContent($('#windowNotice'), emptyState('正在查询窗口批次'));
        try {
            const window = await api(`/campus-windows/${id}`, { signal });
            if (signal.aborted) return;
            $('#windowTitle').textContent = window.name;
            document.title = `${window.name} · 校园取餐`;
            $('#windowAddress').textContent = window.address ? `取餐地点 · ${window.address}` : '窗口暂未填写取餐地址';
            $('#windowAddress').hidden = $('#windowStock').hidden = $('#windowSchedule').hidden = false;
            setContent($('#windowNotice'), '');
            const open = window.batches.filter(batch => batch.bookingOpen);
            const upcoming = window.batches.filter(batch => !batch.bookingOpen);
            $('#windowPortions').textContent = open.reduce((total, batch) => total + batch.available, 0);
            setContent($('#windowOpenMeals'), open.length ? mealCards(open) : emptyState('暂无正在开放的批次'));
            setContent($('#windowUpcomingMeals'), upcoming.length ? mealCards(upcoming) : emptyState('暂无即将开放的批次'));
        } catch (error) {
            if (!signal.aborted) setContent($('#windowNotice'), emptyState('暂时无法查看窗口', error.message));
        }
    }

    function mealCards(batches, showWindow = false) {
        return batches.map(batch => {
            const available = isBookable(batch);
            const status = batch.status;
            const tone = status === 'SOLD_OUT' ? ' is-sold-out' : '';
            const photo = batch.shopName.endsWith('（演示）') ? demoMealPhotos.get(batch.title) : '';
            const photoClass = showWindow ? 'meal-card-thumbnail' : 'meal-card-photo';
            const image = photo ? `<img class="${photoClass}" src="${photo}" alt="" width="623" height="623" loading="lazy" decoding="async">` : '';
            return `<article class="meal-card${photo && !showWindow ? ' has-photo' : ''}${tone}">
                ${showWindow ? '' : image}
                <div class="meal-card-body">
                    <div class="meal-card-header">
                        <div>
                            <div class="meal-card-heading"><h3>${escapeHtml(batch.title)}</h3>${statusBadge(status)}</div>
                            ${showWindow ? `<p><a class="meal-window-link" href="#/window/${escapeHtml(batch.windowId)}">${escapeHtml(batch.shopName)}</a></p>` : ''}
                        </div>
                        ${showWindow ? image : ''}
                    </div>
                    <dl class="meal-times">
                        <div><dt>取餐时段</dt><dd>${formatRange(batch.pickupBeginTime, batch.pickupEndTime)}</dd></div>
                        <div><dt>${batch.bookingOpen ? '预约截止' : '开放预约'}</dt><dd>${formatMoment(batch.bookingOpen ? batch.endTime : batch.beginTime)}</dd></div>
                    </dl>
                    <div class="meal-card-actions">
                        <span class="stock">剩 <strong>${Number(batch.available)}</strong> 份</span>
                        ${status === 'SOLD_OUT'
                            ? `<button type="button" class="secondary-button join-waitlist" data-join="${escapeHtml(batch.id)}">加入候补</button>`
                            : `<button type="button" data-reserve="${escapeHtml(batch.id)}" ${available ? '' : 'disabled'}>预约一份</button>`}
                    </div>
                </div>
            </article>`;
        }).join('');
    }

    function receiptKey() {
        return `foodiego_meal_receipts_${state.user.id}`;
    }

    function receiptIds() {
        const saved = localStorage.getItem(receiptKey()) || '';
        const previous = sessionStorage.getItem(`foodiego_meal_${state.user.id}`);
        return [...new Set([previous, ...saved.split(',')])].filter(id => /^[1-9]\d{0,18}$/.test(id));
    }

    function rememberReservation(receipt) {
        const ids = [receipt.id, ...receiptIds().filter(id => id !== receipt.id)];
        localStorage.setItem(receiptKey(), ids.slice(0, 30).join(','));
    }

    async function reserveMeal(batchId) {
        if (!state.user) {
            signIn(batchId);
            return;
        }
        const token = state.token;
        const button = $(`[data-page="${state.page}"] [data-reserve="${batchId}"]`);
        if (button) button.disabled = true;
        try {
            const receipt = await api(`/meal-batches/${batchId}/reservations`, { method: 'POST' });
            if (state.token !== token) return;
            rememberReservation(receipt);
            if (['meals', 'window'].includes(state.page)) location.hash = `/reservations/${receipt.id}`;
            else if (state.page === 'reservations') renderPage();
        } catch (error) {
            toast(error.message, true);
            if (button) button.disabled = false;
            if (state.page === 'meals') renderPage();
        }
    }

    async function joinWaitlist(button) {
        if (!state.user) {
            const batch = button.dataset.join;
            sessionStorage.setItem(pendingMealKey, `login:waitlist:${batch}`);
            const params = new URLSearchParams({ waitlist: batch, next: '#/waitlist' });
            location.assign(`/login.html?${params}`);
            return;
        }
        await enqueueWaitlist(button.dataset.join, button);
    }

    async function enqueueWaitlist(batch, button) {
        const token = state.token;
        if (button) button.disabled = true;
        try {
            await api(`/meal-batches/${batch}/waitlist`, { method: 'POST' });
            if (token !== state.token) return;
            if (state.page !== 'waitlist') location.hash = '#/waitlist';
            else renderPage();
        } catch (error) {
            if (token === state.token) toast(error.message, true);
        } finally {
            if (button) button.disabled = false;
        }
    }

    async function loadWaitlist(signal) {
        const notice = $('#waitlistNotice');
        const list = $('#waitlistEntries');
        const refresh = $('#refreshWaitlist');
        refresh.hidden = !state.user;
        if (!state.user) {
            setContent(list, '');
            setContent(notice, state.token ? emptyState('正在确认登录状态') :
                emptyState('登录后查看候补', '', '<button type="button" data-login>登录</button>'));
            return;
        }
        try {
            const entries = await api('/meal-waitlist', { signal });
            if (signal.aborted || state.waitlistAction) return;
            setContent(notice, entries.length ? '' : emptyState('还没有候补记录', '餐品约满时可以加入候补。',
                '<a class="button-link" href="#/meals">查看今日餐品</a>'));
            const priority = { OFFERED: 0, WAITING: 1 };
            entries.sort((left, right) => (priority[left.status] ?? 2) - (priority[right.status] ?? 2));
            setContent(list, entries.map(waitlistRow).join(''));
            if (entries.some(entry => ['WAITING', 'OFFERED'].includes(entry.status))) {
                scheduleReload(loadWaitlist, signal, 3000);
            }
        } catch (error) {
            if (signal.aborted) return;
            setContent(list, '');
            setContent(notice, emptyState('候补加载失败', error.message));
        }
    }

    function waitlistRow(entry) {
        const offered = entry.status === 'OFFERED';
        const waiting = entry.status === 'WAITING';
        const ref = `data-waitlist-batch="${escapeHtml(entry.batchId)}" data-attempt="${escapeHtml(entry.attempt)}"`;
        const stateText = offered ? `<strong>已保留一份</strong><p>${escapeHtml(offerTime.format(new Date(entry.expiresAt)))} 前确认</p>` :
            waiting ? `<strong>排队第 ${Number(entry.position)} 位</strong><p>等待余量回补</p>` :
                `<strong>${escapeHtml(waitlistLabels[entry.status])}</strong>`;
        return `<article class="waitlist-row${offered ? ' is-offered' : ''}">
            <div><h2>${escapeHtml(entry.title)}</h2><p>${escapeHtml(entry.shopName)}</p></div>
            <div class="waitlist-state">${stateText}</div>
            <div class="waitlist-actions">
                ${offered ? `<button type="button" data-waitlist-action="confirm" ${ref}>确认预约</button>` : ''}
                ${offered || waiting ? `<button class="secondary-button" type="button" data-waitlist-action="leave" ${ref}>退出候补</button>` : ''}
                ${entry.reservationId ? `<a class="button-link" href="#/reservations/${escapeHtml(entry.reservationId)}">查看预约</a>` : ''}
            </div>
        </article>`;
    }

    async function updateWaitlist(button) {
        if (state.waitlistAction) return;
        const { waitlistAction: action, waitlistBatch: batch, attempt } = button.dataset;
        const token = state.token;
        state.waitlistAction = true;
        button.disabled = true;
        try {
            const receipt = await api(`/meal-waitlist/${batch}/${action}`, {
                method: 'POST', body: JSON.stringify({ attempt })
            });
            if (token !== state.token) return;
            if (action === 'confirm') {
                rememberReservation(receipt);
                if (state.page === 'waitlist') location.hash = `#/reservations/${receipt.id}`;
            } else toast('已退出候补');
        } catch (error) {
            if (token === state.token) toast(error.message, true);
        } finally {
            state.waitlistAction = false;
            if (state.page === 'waitlist') renderPage();
        }
    }

    async function loadReservations(signal) {
        const notice = $('#reservationNotice');
        const historySection = $('#reservationHistory');
        const ticket = $('#reservationTicket');
        const refresh = $('#refreshReservations');
        const pages = $('#reservationPages');
        if (!state.user) {
            ticket.hidden = historySection.hidden = refresh.hidden = true;
            setContent(notice, state.token ? emptyState('正在确认登录状态') :
                emptyState('登录后查看预约', '取餐码和预约进度会显示在这里。', '<button type="button" data-login>登录</button>'));
            return;
        }
        refresh.hidden = false;
        if (ticket.hidden && historySection.hidden) setContent(notice, emptyState('正在加载预约…'));
        $$('[data-reservation-status]').forEach(button => {
            button.setAttribute('aria-pressed', String(button.dataset.reservationStatus === state.reservationStatus));
        });
        try {
            const { reservationId, reservationStatus, reservationPage } = state;
            const history = await api(`/meal-reservations?status=${reservationStatus}&page=${reservationPage}`, { signal });
            if (signal.aborted) return;
            const items = new Map(history.items.map(meal => [meal.id, meal]));
            const next = history.nextPickup;
            const pendingIds = receiptIds().filter(id => !items.has(id) && id !== next?.id);
            const details = new Set(pendingIds);
            if (reservationId && !items.has(reservationId) && reservationId !== next?.id) details.add(reservationId);
            const missing = new Set();
            const confirmed = new Set();
            let detail = null;
            let processing = false;
            await Promise.all(Array.from(details, async id => {
                try {
                    const meal = await api(`/meal-reservations/${id}`, { signal });
                    if (id === reservationId) detail = meal;
                    if (meal.status === 'PROCESSING') processing = true;
                    if (!['PROCESSING', 'FAILED'].includes(meal.status)) confirmed.add(id);
                    else if (reservationStatus === 'ALL' && reservationPage === 1 && pendingIds.includes(id)) items.set(id, meal);
                } catch (error) {
                    if (error.message === '找不到此预约') missing.add(id);
                }
            }));
            if (signal.aborted) return;
            localStorage.setItem(receiptKey(), pendingIds.filter(id => !missing.has(id) && !confirmed.has(id)).join(','));
            sessionStorage.removeItem(`foodiego_meal_${state.user.id}`);
            const meals = [...items.values()];
            if (reservationStatus === 'ALL') meals.sort((left, right) => BigInt(left.id) > BigInt(right.id) ? -1 : 1);
            const selected = reservationId ? items.get(reservationId) || (reservationId === next?.id ? next : detail) :
                next || meals.find(meal => meal.status === 'PROCESSING');
            const empty = !selected && !meals.length && reservationStatus === 'ALL' && reservationPage === 1 && !history.hasNext;
            renderReservationTicket(ticket, selected, next);
            historySection.hidden = empty;
            renderReservationHistory(meals, selected);
            setContent(notice, reservationId && !selected ? emptyState('暂时无法查看这条预约', '',
                '<a class="button-link" href="#/reservations">查看全部预约</a>') : empty ?
                emptyState('还没有预约', '', '<a class="button-link" href="#/meals">查看今日餐品</a>') : '');
            pages.hidden = reservationPage === 1 && !history.hasNext;
            $('span', pages).textContent = `第 ${reservationPage} 页`;
            $('[data-reservation-page="-1"]').disabled = reservationPage === 1;
            $('[data-reservation-page="1"]').disabled = !history.hasNext;
            if (processing || selected?.status === 'PROCESSING' || pendingIds.some(id => confirmed.has(id))) scheduleReload(loadReservations, signal, 2200);
            else if (next || selected?.status === 'AWAITING_PICKUP' || reservationStatus === 'AWAITING_PICKUP') scheduleReload(loadReservations, signal, 8000);
        } catch (error) {
            if (signal.aborted) return;
            ticket.hidden = historySection.hidden = true;
            setContent(notice, emptyState('预约加载失败', error.message));
        }
    }

    function renderReservationHistory(meals, selected) {
        const list = $('#reservationList');
        list.hidden = !meals.length;
        setContent($('#reservationHistoryNotice'), meals.length ? '' : emptyState('没有符合条件的预约'));
        setContent(list, meals.map(meal => `
            <li><a href="#/reservations/${escapeHtml(meal.id)}" ${meal.id === selected?.id ? 'aria-current="true"' : ''}>
                <div><strong>${escapeHtml(meal.title)}</strong><small>${escapeHtml(meal.shopName)}</small></div>
                ${statusBadge(meal.status)}
            </a></li>`).join(''));
    }

    function renderReservationTicket(ticket, selected, next) {
        ticket.hidden = !selected;
        if (!selected) {
            setContent(ticket, '');
            return;
        }
        const nextIsSelected = selected.id === next?.id;
        const code = selected.pickupCode ? `<div class="ticket-code"><dt>取餐码</dt>
            <dd translate="no"><span>${escapeHtml(selected.pickupCode.slice(0, 4))}</span> <span>${escapeHtml(selected.pickupCode.slice(4))}</span></dd>
        </div>` : '';
        const cancel = selected.canCancel ? `<button type="button" class="secondary-button" data-cancel="${escapeHtml(selected.id)}">取消预约</button>` : '';
        const back = next && !nextIsSelected ? '<a class="button-link secondary-button" href="#/reservations">返回下一笔取餐</a>' : '';
        setContent(ticket, `<header class="ticket-heading">
            <h2 id="reservationTicketTitle">${escapeHtml(selected.title)}</h2>
            <div>${nextIsSelected ? '<span class="ticket-next">下一笔取餐</span>' : ''}${statusBadge(selected.status)}</div>
        </header>
        <div class="ticket-body">
            <dl class="ticket-numbers">${code}<div class="ticket-id"><dt>预约号</dt><dd translate="no">${escapeHtml(selected.id)}</dd></div></dl>
            <dl class="ticket-details">
                <div><dt>取餐窗口</dt><dd>${escapeHtml(selected.shopName)}</dd></div>
                ${selected.shopAddress ? `<div><dt>取餐地点</dt><dd>${escapeHtml(selected.shopAddress)}</dd></div>` : ''}
                <div><dt>取餐时段</dt><dd>${formatRange(selected.pickupBeginTime, selected.pickupEndTime)}</dd></div>
            </dl>
        </div>${cancel || back ? `<div class="ticket-actions">${back}${cancel}</div>` : ''}`);
    }

    async function cancelMeal(button) {
        button.disabled = true;
        try {
            await api(`/meal-reservations/${button.dataset.cancel}/cancel`, { method: 'POST' });
            toast('预约已取消');
        } catch (error) {
            toast(error.message, true);
        } finally {
            button.disabled = false;
            if (state.page === 'reservations') renderPage();
        }
    }

    window.addEventListener('hashchange', () => renderPage(true));
    ['#refreshMeals', '#refreshReservations', '#refreshWindow', '#refreshWaitlist'].forEach(selector =>
        $(selector).addEventListener('click', () => renderPage()));
    $('#mealFilters').addEventListener('submit', event => {
        event.preventDefault();
        loadMenu();
    });
    $('#mealFilters').addEventListener('reset', event => {
        if (event.currentTarget.contains(document.activeElement)) $('#mealSearch').focus();
        setTimeout(loadMenu, 0);
    });
    $('#toggleMealFilters').addEventListener('click', event => {
        const options = $('#mealFilterOptions');
        options.hidden = !options.hidden;
        event.currentTarget.setAttribute('aria-expanded', String(!options.hidden));
    });
    $('#mealWindow').addEventListener('change', () => loadMenu());
    $('#mealStatus').addEventListener('change', () => loadMenu());
    $('#mealSearch').addEventListener('input', () => {
        state.menuRequest?.abort();
        clearTimeout(state.menuTimer);
        state.menuTimer = setTimeout(loadMenu, 250);
    });
    $('#allMealWindows').addEventListener('click', () => {
        $('#mealFilters').reset();
        jumpToMenu();
    });
    $('#main').addEventListener('click', event => {
        const button = event.target.closest('button');
        if (!button) return;
        if (button.dataset.reserve) reserveMeal(button.dataset.reserve);
        if (button.dataset.join) joinWaitlist(button);
        if (button.dataset.waitlistAction) updateWaitlist(button);
        if (button.dataset.cancel) cancelMeal(button);
        if (button.dataset.reservationStatus) {
            state.reservationStatus = button.dataset.reservationStatus;
            state.reservationPage = 1;
            renderPage();
        }
        if (button.dataset.reservationPage) {
            state.reservationPage += Number(button.dataset.reservationPage);
            renderPage();
        }
        if (button.hasAttribute('data-login')) signIn();
    });
    renderPage();
    restoreAccount();
})();
