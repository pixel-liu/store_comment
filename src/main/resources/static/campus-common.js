(() => {
    'use strict';

    const $ = (selector, root = document) => root.querySelector(selector);
    const $$ = selector => Array.from(document.querySelectorAll(selector));
    const rendered = new WeakMap();
    const statuses = {
        PROCESSING: '处理中', AWAITING_PICKUP: '待取餐', REDEEMED: '已取餐',
        CANCELLED: '已取消', EXPIRED: '已逾期', FAILED: '预约失败',
        PUBLISHING: '发布中', SCHEDULED: '即将开放', OPEN: '预约中',
        SOLD_OUT: '已约满', CLOSING: '关单处理中', BOOKING_CLOSED: '预约已截止', CLOSED: '已结束'
    };

    function escapeHtml(value) {
        return String(value ?? '').replace(/[&<>'"]/g, character => ({
            '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;'
        })[character]);
    }

    function setContent(node, markup) {
        if (rendered.get(node) === markup) return;
        node.innerHTML = markup;
        rendered.set(node, markup);
    }

    function emptyState(title, detail = '', action = '') {
        return `<div class="empty-state"><strong>${escapeHtml(title)}</strong>
            ${detail ? `<p>${escapeHtml(detail)}</p>` : ''}${action}</div>`;
    }

    function formatMoment(value) {
        return `${Number(value.slice(5, 7))}月${Number(value.slice(8, 10))}日 ${value.slice(11, 16)}`;
    }

    function formatRange(begin, end) {
        const endText = begin.slice(0, 10) === end.slice(0, 10) ? end.slice(11, 16) : formatMoment(end);
        return `${formatMoment(begin)} – ${endText}`;
    }

    function statusBadge(status) {
        const tone = ['PROCESSING', 'PUBLISHING', 'SCHEDULED', 'CLOSING'].includes(status) ? ' pending' :
            ['CANCELLED', 'EXPIRED', 'FAILED', 'SOLD_OUT', 'CLOSED', 'BOOKING_CLOSED'].includes(status) ? ' inactive' : '';
        return `<span class="status${tone}">${escapeHtml(statuses[status] || status)}</span>`;
    }

    function toast(message, error = false) {
        const notice = $('#toast');
        notice.textContent = message;
        notice.classList.toggle('error', error);
        notice.hidden = false;
        clearTimeout(toast.timer);
        toast.timer = setTimeout(() => { notice.hidden = true; }, 3000);
    }

    const sessionKeys = { student: 'foodiego_token', backoffice: 'foodiego_backoffice_token' };
    const pendingMealKey = 'foodiego_pending_reservation';

    async function requestJson(path, options = {}) {
        const apiBase = window.FOODIEGO_API_BASE || '';
        const headers = new Headers(options.headers);
        if (options.body) headers.set('Content-Type', 'application/json');
        const response = await fetch(apiBase + path, { ...options, headers });
        const payload = await response.json().catch(() => null);
        if (!response.ok || !payload?.success) {
            const fallback = response.status === 401 ? '请登录后继续' :
                response.status === 403 ? '此账号没有操作权限' : '请求失败，请重试';
            const error = new Error(payload?.errorMsg || fallback);
            error.status = response.status;
            throw error;
        }
        return payload.data;
    }

    function createSession(state, { storageKey, accountPath, signIn, onChange, onLookupError }) {
        const account = $('#accountButton');
        state.token = localStorage.getItem(storageKey) || '';

        function updateAccount() {
            const name = state.user ? state.user.nickName || '已登录' : '登录';
            setContent(account, `<span>${escapeHtml(name)}</span>`);
        }

        function clearSession() {
            if (localStorage.getItem(storageKey) === state.token) localStorage.removeItem(storageKey);
            Object.assign(state, { token: '', user: null });
            updateAccount();
            onChange();
        }

        async function api(path, options = {}) {
            const token = state.token;
            const headers = new Headers(options.headers);
            if (token) headers.set('Authorization', token);
            try {
                return await requestJson(path, { ...options, headers });
            } catch (error) {
                if (error.status === 401 && token && state.token === token) clearSession();
                throw error;
            }
        }

        async function restoreAccount() {
            const token = state.token;
            if (!token) return;
            try {
                const user = await api(accountPath);
                if (state.token !== token) return;
                state.user = user;
                updateAccount();
                onChange();
            } catch (error) {
                if (state.token !== token) return;
                if (onLookupError) onLookupError(error);
                else clearSession();
            }
        }


        async function accountAction() {
            if (!state.user) {
                signIn();
                return;
            }
            if (!window.confirm('退出当前账号？')) return;
            try {
                await api('/user/logout', { method: 'POST' });
                clearSession();
            } catch (error) {
                if (state.user) toast(error.message, true);
            }
        }

        $('.skip-link').addEventListener('click', event => {
            event.preventDefault();
            $('#main').focus();
        });
        account.addEventListener('click', accountAction);
        return { api, restoreAccount };
    }

    window.Campus = {
        $, $$, escapeHtml, setContent, emptyState, formatMoment, formatRange, statusBadge, toast,
        createSession, requestJson, sessionKeys, pendingMealKey
    };
})();
