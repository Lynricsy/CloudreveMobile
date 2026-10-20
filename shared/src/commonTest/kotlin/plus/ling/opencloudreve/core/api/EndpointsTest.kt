package plus.ling.opencloudreve.core.api

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.parseUrlEncodedParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import plus.ling.opencloudreve.core.api.endpoints.AdminApi
import plus.ling.opencloudreve.core.api.endpoints.DeviceApi
import plus.ling.opencloudreve.core.api.endpoints.FileApi
import plus.ling.opencloudreve.core.api.endpoints.SessionApi
import plus.ling.opencloudreve.core.api.endpoints.ShareApi
import plus.ling.opencloudreve.core.api.endpoints.SiteApi
import plus.ling.opencloudreve.core.api.endpoints.UserApi
import plus.ling.opencloudreve.core.api.endpoints.WorkflowApi
import plus.ling.opencloudreve.core.auth.model.CaptchaParams
import plus.ling.opencloudreve.core.model.AdminUserPatch
import plus.ling.opencloudreve.core.model.AdminUserUpsertRequest
import plus.ling.opencloudreve.core.model.CrUri
import plus.ling.opencloudreve.core.model.FileUrlRequest
import plus.ling.opencloudreve.core.model.GroupPermission
import plus.ling.opencloudreve.core.model.NavigatorCapability
import plus.ling.opencloudreve.core.model.SearchParams
import plus.ling.opencloudreve.core.model.TaskCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EndpointsTest {

    private fun body(req: io.ktor.client.request.HttpRequestData) = Json.parseToJsonElement(req.bodyText()).jsonObject

    @Test
    fun listDirectoryDecodesListPage() = runTest {
        val server = MockServer {
            json(
                """{"code":0,"msg":"","data":{
                  "files":[
                    {"type":1,"id":"f1","name":"Docs","created_at":"2026-10-01T08:00:00.123456789+08:00","updated_at":"2026-10-02T00:00:00Z","size":0,"metadata":null,"path":"cloudreve://my/Docs","capability":"w8edAQ==","owned":true},
                    {"type":0,"id":"f2","name":"link.pdf","created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z","size":10,"metadata":{"sys:shared_redirect":"cloudreve://Ab:pw@share/x.pdf"},"path":"cloudreve://my/link.pdf","primary_entity":"e1"}
                  ],
                  "parent":{"type":1,"id":"","name":"","created_at":"0001-01-01T00:00:00Z","updated_at":"0001-01-01T00:00:00Z","size":0,"metadata":{}},
                  "pagination":{"page":0,"page_size":100,"next_token":"tok2","is_cursor":true},
                  "props":{"capability":"w8edAQ==","max_page_size":2000,"order_by_options":["name","size","updated_at","created_at"],"order_direction_options":["asc","desc"]},
                  "context_hint":"8c6f0d3e-0000-4000-8000-000000000000",
                  "mixed_type":true,
                  "storage_policy":{"id":"p1","name":"Default","type":"local","max_size":0,"relay":true},
                  "view":{"page_size":100,"order":"name","order_direction":"asc","view":"grid","thumbnail":true}
                }}""",
            )
        }
        val page = FileApi(server.client()).list("cloudreve://my", pageSize = 100, nextPageToken = "tok1")
        val req = server.requests.single()
        assertEquals("/file", req.apiPath)
        assertEquals("cloudreve://my", req.url.parameters["uri"])
        assertEquals("tok1", req.url.parameters["next_page_token"])
        assertEquals("100", req.url.parameters["page_size"])
        assertNull(req.url.parameters["page"])
        assertNull(req.url.parameters["hash"])

        assertEquals(2, page.files.size)
        val docs = page.files[0]
        assertTrue(docs.isFolder)
        assertTrue(docs.metadata.isEmpty())
        assertTrue(docs.can(NavigatorCapability.ENTER_FOLDER))
        assertFalse(docs.can(NavigatorCapability.RESTORE))
        val link = page.files[1]
        assertTrue(link.isSymbolic)
        assertEquals("cloudreve://Ab:pw@share/x.pdf", link.linkedUri)
        assertEquals("pdf", link.extension)
        val parent = page.parent!!
        assertEquals("", parent.id)
        assertNull(parent.uri())
        assertTrue(page.pagination.isCursor)
        assertTrue(page.pagination.hasMore)
        assertEquals("tok2", page.pagination.nextToken)
        assertEquals(2000, page.props.maxPageSize)
        assertTrue(page.mixedType)
        assertEquals("local", page.storagePolicy!!.type)
        assertEquals("grid", page.view!!.view)
        assertEquals("8c6f0d3e-0000-4000-8000-000000000000", page.contextHint)
    }

    @Test
    fun searchListingMergesSseStream() = runTest {
        val sse = buildString {
            append("event: file\n")
            append("data:[{\"type\":0,\"id\":\"a\",\"name\":\"a.pdf\",\"size\":1,\"metadata\":{},\"path\":\"cloudreve://my/a.pdf\"}]\n\n")
            append("event: file\n")
            append("data:[{\"type\":0,\"id\":\"b\",\"name\":\"b.pdf\",\"size\":2,\"metadata\":{},\"path\":\"cloudreve://my/b.pdf\"}]\n\n")
            append("event: list\n")
            append("data:{\"files\":[],\"pagination\":{\"page\":0,\"page_size\":100,\"is_cursor\":true},\"props\":{\"max_page_size\":2000,\"order_by_options\":null},\"mixed_type\":false}\n\n")
        }
        val server = MockServer { json(sse, contentType = "text/event-stream") }
        val uri = CrUri.my().withSearch(SearchParams(names = listOf("*.pdf"))).toString()
        val page = FileApi(server.client()).list(uri)
        assertEquals(listOf("a", "b"), page.files.map { it.id })
        assertFalse(page.pagination.hasMore)
        assertNull(page.props.orderByOptions)
    }

    @Test
    fun searchListingFallsBackToEnvelope() = runTest {
        val server = MockServer { json("""{"code":0,"msg":"","data":{"files":[{"id":"x","name":"x","metadata":{}}],"pagination":{}}}""") }
        val uri = CrUri.my().withSearch(SearchParams(names = listOf("x"))).toString()
        assertEquals("x", FileApi(server.client()).list(uri).files.single().id)
    }

    @Test
    fun fileUrlSendsBodyAndContextHint() = runTest {
        val server = MockServer {
            json("""{"code":0,"msg":"","data":{"urls":[{"url":"https://s3/x?sig"},{"url":""}],"expires":"2026-10-20T12:00:00Z"}}""")
        }
        val res = FileApi(server.client()).url(
            FileUrlRequest(uris = listOf("cloudreve://my/a", "cloudreve://my/b"), download = true, skipError = true),
            contextHint = "hint-1",
        )
        val req = server.requests.single()
        assertEquals(HttpMethod.Post, req.method)
        assertEquals("/file/url", req.apiPath)
        assertEquals("hint-1", req.headers[ApiClient.HEADER_CONTEXT_HINT])
        val b = body(req)
        assertEquals("true", b["download"]!!.jsonPrimitive.content)
        assertEquals("true", b["skip_error"]!!.jsonPrimitive.content)
        assertNull(b["entity"])
        assertEquals("", res.urls[1].url)
        assertEquals("2026-10-20T12:00:00Z", res.expires.toString())
    }

    @Test
    fun sourcePartialSuccessReturnsLinksAndFailures() = runTest {
        val server = MockServer {
            json(
                """{"code":40081,"msg":"partial","data":[{"link":"https://x/f/1/a","file_url":"cloudreve://my/a"}],
                   "aggregated_error":{"cloudreve://my/b":{"code":40007,"msg":"Group not allowed"}}}""",
            )
        }
        val res = FileApi(server.client()).source(listOf("cloudreve://my/a", "cloudreve://my/b"))
        assertEquals(HttpMethod.Put, server.requests.single().method)
        assertEquals("https://x/f/1/a", res.links.single().link)
        assertEquals(40007, res.failures["cloudreve://my/b"]!!.code)

        val failing = MockServer { json("""{"code":40014,"msg":"too many"}""") }
        assertEquals(40014, assertFailsWith<ApiException> { FileApi(failing.client()).source(listOf("a")) }.code)
    }

    @Test
    fun putContentSendsRawBytesWithPrevious() = runTest {
        val server = MockServer { json("""{"code":0,"msg":"","data":{"id":"f","name":"a.md","metadata":{},"primary_entity":"e2"}}""") }
        val saved = FileApi(server.client()).putContent("cloudreve://my/a.md", "# hi".encodeToByteArray(), previous = "e1")
        val req = server.requests.single()
        assertEquals(HttpMethod.Put, req.method)
        assertEquals("/file/content", req.apiPath)
        assertEquals("e1", req.url.parameters["previous"])
        assertEquals("# hi", req.bodyText())
        assertEquals("application/octet-stream", req.body.contentType.toString())
        assertEquals("e2", saved.primaryEntity)

        val stale = MockServer { json("""{"code":40076,"msg":"File is updated during your edit"}""") }
        assertEquals(ErrorCode.STALE_VERSION, assertFailsWith<ApiException> {
            FileApi(stale.client()).putContent("cloudreve://my/a.md", ByteArray(0), "e1")
        }.code)
    }

    @Test
    fun fileOperationsUseDocumentedShapes() = runTest {
        val server = MockServer { req ->
            if (req.apiPath == "/file/info") json("""{"code":0,"msg":"","data":{"id":"f","name":"a","metadata":{}}}""")
            else json("""{"code":0,"msg":""}""")
        }
        val api = FileApi(server.client())
        api.delete(listOf("cloudreve://my/a"), skipSoftDelete = true)
        api.move(listOf("cloudreve://Ab@share/x"), "cloudreve://my", copy = true)
        api.patchView("cloudreve://trash", null)
        api.setVersion("cloudreve://my/a", "e9")
        api.emptyTrash()
        api.unlock(listOf("t1"))
        api.info(uri = "cloudreve://my/a", extended = true)

        val r = server.requests
        assertEquals(HttpMethod.Delete, r[0].method)
        assertEquals("""{"uris":["cloudreve://my/a"],"unlink":false,"skip_soft_delete":true}""", r[0].bodyText())
        assertEquals("""{"uris":["cloudreve://Ab@share/x"],"dst":"cloudreve://my","copy":true}""", r[1].bodyText())
        assertEquals(HttpMethod.Patch, r[2].method)
        assertEquals("""{"uri":"cloudreve://trash"}""", r[2].bodyText())
        assertEquals(HttpMethod.Post, r[3].method)
        assertEquals("/file/version/current", r[3].apiPath)
        assertEquals("/file/trash", r[4].apiPath)
        assertEquals("", r[4].bodyText())
        assertEquals("/file/lock", r[5].apiPath)
        assertEquals("true", r[6].url.parameters["extended"])
        assertNull(r[6].url.parameters["folder_summary"])
        assertNull(r[6].url.parameters["id"])
    }

    @Test
    fun loginFlattensCaptchaAnd2faSurfaces203() = runTest {
        val server = MockServer { json("""{"code":203,"msg":"","data":"sess-1"}""") }
        val e = assertFailsWith<ApiException> {
            SessionApi(server.client()).login("a@b.c", "secret", CaptchaParams(captcha = "abcd", ticket = "t1"))
        }
        assertEquals(203, e.code)
        assertEquals("sess-1", e.data!!.jsonPrimitive.content)
        assertEquals(
            """{"email":"a@b.c","password":"secret","captcha":"abcd","ticket":"t1"}""",
            server.requests.single().bodyText(),
        )
    }

    @Test
    fun oauthTokenUsesFormAndParsesBareResponses() = runTest {
        val ok = MockServer {
            json("""{"access_token":"at","token_type":"Bearer","expires_in":3600,"refresh_token_expires_in":7776000,"refresh_token":"rt","scope":"openid profile"}""")
        }
        val token = SessionApi(ok.client()).oauthToken("cid", "sec", "code-1", "verifier", "opencloudreve://oauth/callback")
        assertEquals("at", token.accessToken)
        assertEquals("rt", token.refreshToken)
        val req = ok.requests.single()
        assertEquals("/session/oauth/token", req.apiPath)
        assertTrue(req.body.contentType.toString().startsWith("application/x-www-form-urlencoded"))
        val form = req.bodyText().parseUrlEncodedParameters()
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("verifier", form["code_verifier"])
        assertEquals("opencloudreve://oauth/callback", form["redirect_uri"])
        assertEquals("sec", form["client_secret"])

        val bad = MockServer {
            json("""{"error":"invalid_grant","error_description":"Invalid code verifier","error_codes":[40020],"correlation_id":"c"}""", HttpStatusCode.BadRequest)
        }
        val e = assertFailsWith<ApiException> { SessionApi(bad.client()).oauthToken("cid", "sec", "c", "v", "r") }
        assertEquals(ErrorCode.CREDENTIAL_INVALID, e.code)
        assertEquals("Invalid code verifier", e.serverMsg)
    }

    @Test
    fun handoffCreateAndExchange() = runTest {
        val server = MockServer { req ->
            when (req.apiPath) {
                "/session/handoff" -> json("""{"code":0,"msg":"","data":{"code":"h-123"}}""")
                else -> json(
                    """{"code":0,"msg":"","data":{"user":{"id":"u1","nickname":"n","created_at":"2026-01-01T00:00:00Z"},
                       "token":{"access_token":"a","refresh_token":"r","access_expires":"2026-10-20T01:00:00Z","refresh_expires":"2026-11-03T00:00:00Z"}}}""",
                )
            }
        }
        val api = SessionApi(server.client())
        assertEquals("h-123", api.handoffCreate().code)
        val login = api.handoffExchange("h-123")
        assertEquals("u1", login.user.id)
        assertEquals("r", login.token.refreshToken)
        assertEquals(HttpMethod.Post, server.requests[0].method)
        assertEquals("""{"code":"h-123"}""", server.requests[1].bodyText())
    }

    @Test
    fun siteConfigSections() = runTest {
        val server = MockServer { req ->
            when (req.apiPath) {
                "/site/config/basic" -> json("""{"code":0,"msg":"","data":{"title":"My Cloud","captcha_type":"turnstile","turnstile_site_id":"1x00","user":{"id":"","nickname":"","anonymous":true}}}""")
                "/site/config/explorer" -> json(
                    """{"code":0,"msg":"","data":{"max_batch_size":3000,"file_viewers":[{"viewers":[{"id":"image","type":"builtin","display_name":"Image","exts":["png","jpg"]}]}],
                       "default_viewer_mapping":{"md":"markdown"},"icons":"[{\"exts\":[\"mp3\"],\"icon\":\"audio\",\"color\":\"#651fff\"}]",
                       "custom_props":[{"id":"rate","name":"Rate","type":"rating","max":5}]}}""",
                )
                "/site/config/emojis" -> json("""{"code":0,"msg":"","data":{"emoji_preset":"{\"😀\":[\"😀\",\"😁\"]}"}}""")
                else -> json("""{"code":0,"msg":"","data":{}}""")
            }
        }
        val api = SiteApi(server.client())
        val basic = api.basicConfig()
        assertEquals("turnstile", basic.captchaType)
        assertTrue(basic.user!!.anonymous)
        val login = api.loginConfig()
        assertFalse(login.loginCaptcha)
        val explorer = api.explorerConfig()
        assertEquals("image", explorer.viewers.single().id)
        assertEquals("audio", explorer.parsedIcons().single().icon)
        assertEquals("rating", explorer.customProps.single().type)
        assertEquals(listOf("😀", "😁"), api.emojisConfig().parsedPreset()["😀"])
        assertEquals("My Cloud", SiteApi.decodeBasic(api.config("basic")).title)
    }

    @Test
    fun shareInfoLockedAndCreate() = runTest {
        val server = MockServer { req ->
            when (req.method) {
                HttpMethod.Put -> json("""{"code":0,"msg":"","data":"https://cloud.example.com/s/Ab/pw12"}""")
                else -> json(
                    """{"code":0,"msg":"","data":{"id":"Ab","name":"Docs","visited":3,"unlocked":false,"expired":false,
                       "url":"https://cloud.example.com/s/Ab","created_at":"2026-10-01T00:00:00Z","owner":{"id":"u1","nickname":"Owner"},
                       "size":0,"source_type":1,"password_protected":true}}""",
                )
            }
        }
        val api = ShareApi(server.client())
        val share = api.info("Ab", countViews = true)
        assertFalse(share.unlocked)
        assertTrue(share.passwordProtected)
        assertTrue(share.isFolder)
        assertEquals("Owner", share.owner!!.nickname)
        assertEquals("true", server.requests[0].url.parameters["count_views"])
        assertNull(server.requests[0].url.parameters["password"])
        assertEquals("cloudreve://Ab:pw@share", share.rootUri("pw").toString())

        val url = api.create(
            plus.ling.opencloudreve.core.model.ShareCreateRequest(uri = "cloudreve://my/Docs", isPrivate = true, expire = 86400),
        )
        assertEquals("https://cloud.example.com/s/Ab/pw12", url)
        val b = body(server.requests[1])
        assertEquals("86400", b["expire"]!!.jsonPrimitive.content)
        assertEquals("0", b["downloads"]!!.jsonPrimitive.content)
        assertNull(b["password"])
    }

    @Test
    fun workflowDownloadSummary() = runTest {
        val server = MockServer {
            json(
                """{"code":0,"msg":"","data":{"tasks":[{"id":"t1","type":"remote_download","status":"processing",
                   "created_at":"2026-10-20T00:00:00Z","updated_at":"2026-10-20T00:01:00Z",
                   "node":{"id":"n1","name":"Master","type":"master","capabilities":"Dg=="},
                   "summary":{"phase":"monitor","props":{"src_str":"magnet:?xt=1","dst":"cloudreve://my/dl",
                     "download":{"name":"ubuntu.iso","state":"downloading","total":100,"downloaded":40,"download_speed":5,"uploaded":0,"upload_speed":0,
                       "files":[{"index":0,"name":"ubuntu.iso","size":100,"progress":0.4,"selected":true}]}}},
                   "duration":1200}],"pagination":{"page":0,"page_size":50,"is_cursor":true}}}""",
            )
        }
        val res = WorkflowApi(server.client()).list(TaskCategory.DOWNLOADING)
        assertEquals("downloading", server.requests.single().url.parameters["category"])
        val task = res.tasks.single()
        val summary = task.summary!!
        assertEquals("monitor", summary.phase)
        assertEquals("cloudreve://my/dl", summary.string("dst"))
        val dl = summary.download!!
        assertEquals(40, dl.downloaded)
        assertEquals(0.4, dl.files.single().progress)
        assertTrue(task.node!!.capabilities!!.has(3))
        assertEquals(1200, task.duration)
    }

    @Test
    fun userEndpoints() = runTest {
        val server = MockServer { req ->
            when (req.apiPath) {
                "/user/setting" -> json(
                    """{"code":0,"msg":"","data":{"version_retention_enabled":false,"passwordless":false,"two_fa_enabled":true,
                       "passkeys":[{"id":"cred==","name":"Pixel","created_at":"2026-10-01T00:00:00Z"}],"disable_view_sync":false,"share_links_in_profile":""}}""",
                )
                else -> json("""{"code":0,"msg":""}""")
            }
        }
        val api = UserApi(server.client())
        val settings = api.settings()
        assertTrue(settings.twoFaEnabled)
        assertEquals("cred==", settings.passkeys.single().id)
        api.deletePasskey("cred==")
        api.useGravatar()
        assertEquals("cred==", server.requests[1].url.parameters["id"])
        assertEquals(HttpMethod.Put, server.requests[2].method)
        assertEquals(0, server.requests[2].bodyBytes().size)
        assertEquals("https://cloud.example.com/api/v4/user/avatar/u1?nocache=true", api.avatarUrl("u1", noCache = true))
    }

    @Test
    fun davAccountOptions() = runTest {
        val server = MockServer {
            json(
                """{"code":0,"msg":"","data":{"accounts":[{"id":"d1","created_at":"2026-10-01T00:00:00Z","name":"NAS",
                   "uri":"cloudreve://my/nas","password":"abcdefghijklmnopqrstuvwxyzabcdef","options":"BQ=="}],
                   "pagination":{"page":0,"page_size":50,"is_cursor":true}}}""",
            )
        }
        val api = DeviceApi(server.client())
        val acc = api.davAccounts().accounts.single()
        assertTrue(acc.readonly)
        assertFalse(acc.proxy)
        assertTrue(acc.disableSysFiles)
        assertEquals("50", server.requests.single().url.parameters["page_size"])
        assertEquals("https://cloud.example.com/dav", api.davUrl())
    }

    @Test
    fun adminUserListUpdateAndNodeTest() = runTest {
        val server = MockServer { req ->
            when {
                req.apiPath == "/admin/user" -> json(
                    """{"code":0,"msg":"","data":{"users":[{"id":2,"created_at":"2026-10-01T00:00:00Z","updated_at":"2026-10-01T00:00:00Z",
                       "email":"u@x.y","nick":"U","status":"active","storage":1024,"avatar":"file","group_users":3,
                       "edges":{"group":{"id":3,"name":"Users","permissions":"hAA="}},"hash_id":"lxEu","two_fa_enabled":true}],
                       "pagination":{"page":0,"page_size":20,"total_items":1}}}""",
                )
                req.apiPath == "/admin/user/2" -> json(
                    """{"code":0,"msg":"","data":{"id":2,"email":"new@x.y","nick":"U","status":"active","avatar":"file","group_users":3,"edges":{},"capacity":{"total":10,"used":1}}}""",
                )
                req.apiPath == "/admin/node/1" -> json(
                    """{"code":0,"msg":"","data":{"id":1,"name":"Slave","type":"slave","server":"http://slave:5212","slave_key":"secret-key","edges":{}}}""",
                )
                else -> json("""{"code":0,"msg":""}""")
            }
        }
        val api = AdminApi(server.client())
        val list = api.users(plus.ling.opencloudreve.core.model.AdminListRequest(conditions = mapOf("user_email" to "u@")))
        val u = list.users.single()
        assertEquals("cloudreve://lxEu@my", u.filesRoot().toString())
        assertTrue(u.edges.group!!.permissions!!.has(GroupPermission.SHARE))
        assertFalse(list.pagination.hasMore)
        val listBody = body(server.requests[0])
        assertEquals("1", listBody["page"]!!.jsonPrimitive.content)
        assertEquals("desc", listBody["order_direction"]!!.jsonPrimitive.content)

        val updated = api.updateUser(AdminUserUpsertRequest(AdminUserPatch.from(u).copy(email = "new@x.y")))
        assertEquals(10, updated.capacity!!.total)
        val upd = server.requests[1]
        assertEquals(HttpMethod.Put, upd.method)
        assertEquals(
            """{"user":{"id":2,"email":"new@x.y","nick":"U","avatar":"file","status":"active","group_users":3}}""",
            upd.bodyText(),
        )

        api.testNode(1)
        val test = body(server.requests[3])
        // slave_key 原样透传给测试接口
        assertEquals("secret-key", test["node"]!!.jsonObject["slave_key"]!!.jsonPrimitive.content)
    }
}
