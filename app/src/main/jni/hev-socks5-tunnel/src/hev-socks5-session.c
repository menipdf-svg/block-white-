/*
 ============================================================================
 Name        : hev-socks5-session.c
 Author      : hev <r@hev.cc>
 Copyright   : Copyright (c) 2017 - 2023 hev
 Description : Socks5 Session
 ============================================================================
 */

#include <stdio.h>
#include <string.h>

#include "hev-utils.h"
#include "hev-compiler.h"
#include "hev-logger.h"
#include "hev-config.h"
#include "hev-socks5-client.h"

#include "hev-socks5-session.h"

void
hev_socks5_session_run (HevSocks5Session *self)
{
    HevSocks5SessionIface *iface;
    HevConfigServer *srv;
    int res;

    LOG_D ("%p socks5 session run", self);

    srv = hev_config_get_socks5_server ();

    res = hev_socks5_client_connect (HEV_SOCKS5_CLIENT (self), srv->addr,
                                     srv->port);
    if (res < 0) {
        LOG_I ("%p socks5 session connect", self);
        return;
    }

    if (srv->user && srv->pass) {
        HevSocks5SessionData *data;
        const char *user = srv->user;

        /* WhitelistFilter: use the per-session username when available */
        data = container_of (hev_socks5_session_get_node (self),
                             HevSocks5SessionData, node);
        if (data->user[0])
            user = data->user;

        hev_socks5_client_set_auth (HEV_SOCKS5_CLIENT (self), user, srv->pass);
        LOG_D ("%p socks5 client auth %s", self, user);
    }

    res = hev_socks5_client_handshake (HEV_SOCKS5_CLIENT (self), srv->pipeline);
    if (res < 0) {
        LOG_I ("%p socks5 session handshake", self);
        return;
    }

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    iface->splicer (self);
}

void
hev_socks5_session_terminate (HevSocks5Session *self)
{
    HevSocks5SessionIface *iface;

    LOG_D ("%p socks5 session terminate", self);

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    hev_socks5_set_timeout (HEV_SOCKS5 (self), 0);
    hev_task_wakeup (iface->get_task (self));
}

void
hev_socks5_session_set_task (HevSocks5Session *self, HevTask *task)
{
    HevSocks5SessionIface *iface;

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    iface->set_task (self, task);
}

HevListNode *
hev_socks5_session_get_node (HevSocks5Session *self)
{
    HevSocks5SessionIface *iface;

    iface = HEV_OBJECT_GET_IFACE (self, HEV_SOCKS5_SESSION_TYPE);
    return iface->get_node (self);
}

int
hev_socks5_session_bind (HevSocks5 *self, int fd, const struct sockaddr *dest)
{
    HevConfigServer *srv;
    unsigned int mark;

    LOG_D ("%p socks5 session bind", self);

    srv = hev_config_get_socks5_server ();
    mark = srv->mark;

    if (mark) {
        int res;

        res = set_sock_mark (fd, mark);
        if (res < 0)
            return -1;
    }

    set_sock_tcp_fastopen (fd, srv->fastopen);

    return 0;
}

/*
 * WhitelistFilter: encode where the connection came from into the socks5
 * username, "<user>|<proto>|<src ip>|<src port>|<dst ip>|<dst port>", so the
 * local socks5 server can find out which app owns the connection. The
 * destination is the original one, before any mapped DNS translation.
 */
void
hev_socks5_session_set_origin (HevSocks5SessionData *data, char proto,
                               const ip_addr_t *src, u16_t src_port,
                               const ip_addr_t *dst, u16_t dst_port)
{
    HevConfigServer *srv = hev_config_get_socks5_server ();
    char sbuf[64];
    char dbuf[64];

    data->user[0] = '\0';
    if (!srv->user)
        return;

    if (!ipaddr_ntoa_r (src, sbuf, sizeof (sbuf)))
        return;
    if (!ipaddr_ntoa_r (dst, dbuf, sizeof (dbuf)))
        return;

    snprintf (data->user, sizeof (data->user), "%s|%c|%s|%u|%s|%u", srv->user,
              proto, sbuf, src_port, dbuf, dst_port);
    if (strlen (data->user) > 255)
        data->user[0] = '\0';
}

void *
hev_socks5_session_iface (void)
{
    static char type;

    return &type;
}
