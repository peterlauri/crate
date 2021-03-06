.. highlight:: sh

.. _conf-enterprise-features:

===================
Enterprise Features
===================

.. NOTE::

   CrateDB includes enterprise features that are enabled by default for you to
   try. Before you deploy CrateDB into production, you must `request an
   Enterprise Edition license`_ or disable the Enterprise Edition edition by
   setting the ``license.enterprise`` config parameter to ``false``. Your use
   of the `Enterprise Edition`_ is governed by the terms and conditions of your
   `Enterprise Subscription Agreement`_ with Crate.io.

.. _`Enterprise Edition`: https://crate.io/enterprise-edition/
.. _request an Enterprise Edition license: https://crate.io/enterprise-edition/
.. _Enterprise Subscription Agreement: https://crate.io/subscription_agreement/

.. rubric:: Table of Contents

.. contents::
   :local:

.. _conf_enterprise_license:

Enterprise License
------------------

**license.enterprise**
  | *Default:*  ``true``
  | *Runtime:*  ``no``

  Setting this to ``false`` disables the `Enterprise Edition`_ of CrateDB.

.. _conf_lang_js_enabled:

Javascript Language
-------------------

**lang.js.enabled**
  | *Default:*  ``false``
  | *Runtime:*  ``no``

  Setting to enable the Javascript language. As The Javascript language is an
  experimental feature and is not securely sandboxed its disabled by default.

Authentication
--------------

.. _host_based_auth:

Trust Authentication
....................

**auth.trust.http_default_user**
  | *Runtime:* ``no``
  | *Default:* ``crate``

  The default user that should be used for authentication when clients connect
  to CrateDB via HTTP protocol and they do not specify a user via the
  ``Authorization`` request header.

Host Based Authentication
.........................

Authentication settings (``auth.host_based.*``) are node settings, which means
that their values apply only to the node where they are applied and different
nodes may have different authentication settings.

**auth.host_based.enabled**
  | *Runtime:* ``no``
  | *Default:* ``false``

  Setting to enable or disable Host Based Authentication (HBA). It is disabled
  by default.

HBA Entries
```````````

The ``auth.host_based.config.`` setting is a group setting that can have zero,
one or multiple groups that are defined by their group key (``${order}``) and
their fields (``user``, ``address``, ``method``, ``protocol``, ``ssl``).

**${order}:**
  | An identifier that is used as a natural order key when looking up the host
  | based configuration entries. For example, an order key of ``a`` will be
  | looked up before an order key of ``b``. This key guarantees that the entry
  | lookup order will remain independent from the insertion order of the
  | entries.

The :ref:`admin_hba` setting is a list of predicates that users can specify to
restrict or allow access to CrateDB.

The meaning of the fields of the are as follows:

**auth.host_based.config.${order}.user**
  | *Runtime:*  ``no``

  | Specifies an existing CrateDB username, only ``crate`` user (superuser) is
  | available. If no user is specified in the entry, then all existing users
  | can have access.

**auth.host_based.config.${order}.address**
  | *Runtime:* ``no``

  | The client machine addresses that the client matches, and which are allowed
  | to authenticate. This field may contain an IPv4 address, an IPv6 address or
  | an IPv4 CIDR mask. For example: ``127.0.0.1`` or ``127.0.0.1/32``. It also
  | may contain the special ``_local_`` notation which will match both IPv4 and
  | IPv6 connections from localhost. If no address is specified in the entry,
  | then access to CrateDB is open for all hosts.

**auth.host_based.config.${order}.method**
  | *Runtime:* ``no``

  | The authentication method to use when a connection matches this entry.
  | Valid values are ``trust``, ``cert``, and ``password``. If no method is
  | specified, the ``trust`` method is used by default.
  | See :ref:`auth_trust`, :ref:`auth_cert` and :ref:`auth_password` for more
  | information about these methods.

**auth.host_based.config.${order}.protocol**
  | *Runtime:* ``no``

  | Specifies the protocol for which the authentication entry should be used.
  | If no protocol is specified, then this entry will be valid for all
  | protocols that rely on host based authentication see :ref:`auth_trust`).

**auth.host_based.config.${order}.ssl**
  | *Runtime:* ``no``
  | *Default:* ``optional``

  | Specifies whether the client must use SSL/TLS to connect to the cluster.
  | If set to ``on`` then the client must be connected through SSL/TLS
  | otherwise is not authenticated. If set to ``off`` then the client must
  | *not* be connected via SSL/TLS otherwise is not authenticated. Finally
  | ``optional``, which is the value when the option is completely skipped,
  | means that the client can be authenticated regardless of SSL/TLS is used
  | or not.

.. NOTE::

  **auth.host_based.config.${order}.ssl** is available only for ``pg``
  protocol.

**Example of config groups:**

.. code-block:: yaml

    auth.host_based.config:
      entry_a:
        user: crate
        address: 127.16.0.0/16
      entry_b:
        method: trust
      entry_3:
        user: crate
        address: 172.16.0.0/16
        method: trust
        protocol: pg
        ssl: on

.. _ssl_config:

Secured Communications (SSL/TLS)
--------------------------------

Secured communications via SSL allows you to encrypt traffic between CrateDB
nodes and clients connecting to them. Connections are secured using Transport
Layer Security (TLS).

**ssl.http.enabled**
  | *Runtime:*  ``no``
  | *Default:* ``false``

  Set this to true to enable secure communication between the CrateDB node
  and the client through SSL via the HTTPS protocol.

**ssl.psql.enabled**
  | *Runtime:*  ``no``
  | *Default:* ``false``

  Set this to true to enable secure communication between the CrateDB node
  and the client through SSL via the PostgreSQL wire protocol.

.. _ssl_ingestion_mqtt_enabled:

**ssl.ingestion.mqtt.enabled**
  | *Runtime:*  ``no``
  | *Default:* ``false``

  Set this to true to enable secure communication between the CrateDB node and
  the client through SSL via the MQTT protocol.

**ssl.keystore_filepath**
  | *Runtime:* ``no``

  The full path to the node keystore file.

**ssl.keystore_password**
  | *Runtime:* ``no``

  The password used to decrypt the keystore file defined with
  ``ssl.keystore_filepath``.

**ssl.keystore_key_password**
  | *Runtime:* ``no``

  The password entered at the end of the ``keytool -genkey command``.

.. NOTE::

    Optionally trusted CA certificates can be stored separately from the
    node's keystore into a truststore for CA certificates.

**ssl.truststore_filepath**
  | *Runtime:* ``no``

  The full path to the node truststore file. If not defined, then only a
  keystore will be used.

**ssl.truststore_password**
  | *Runtime:* ``no``

  The password used to decrypt the truststore file defined with
  ``ssl.truststore_filepath``.
