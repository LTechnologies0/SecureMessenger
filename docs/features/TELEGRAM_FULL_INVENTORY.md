# Telegram / TDLib — Full Feature Inventory

Worktree: `SecureMessenger-worktrees/parity-telegram` · Branch: `feat/parity-telegram`

Sources:
- TDLib AAR `protocol/telegram/libs/tdlib-core-0.1.0.aar` (`org.drinkless.tdlib.TdApi`, **1023** `Function` subclasses via `javap` / class scan)
- Official docs: https://core.telegram.org/tdlib/docs · scheme https://core.telegram.org/tdlib/docs/
- SecureMessenger adapter: `protocol/telegram/**` (`TelegramProtocol`, `TdLibFacade`, `TdLibMapper`)

Policy (fail-closed / document only):
- **Secret chats**: `SetTdlibParameters.useSecretChats = false`
- **VoIP**: no tgcalls / WebRTC natives packaged
- **Premium payments**: not implemented by product policy
- **Tor**: SOCKS5 proxy required before parameters

Status legend: `present` · `partial` · `missing` · `blocked`

| ID | Feature | API (TdApi) | Status | Notes |
|----|---------|-------------|--------|-------|
| TG-AUTH-01 | Auth phone + SMS/call/flash/missed/Fragment | `SetAuthenticationPhoneNumber, CheckAuthenticationCode, ResendAuthenticationCode, AuthenticationCodeType*` | **present** | TelegramProtocol.onAuthorizationState + continueAuthentication |
| TG-AUTH-02 | Auth 2FA cloud password | `CheckAuthenticationPassword, AuthorizationStateWaitPassword` | **present** |  |
| TG-AUTH-03 | Auth registration (new user) | `RegisterUser, AuthorizationStateWaitRegistration` | **present** |  |
| TG-AUTH-04 | Auth other-device / QR confirmation | `AuthorizationStateWaitOtherDeviceConfirmation` | **partial** | State surfaced; QR UX limited |
| TG-AUTH-05 | Auth email address + email code | `SetAuthenticationEmailAddress, CheckAuthenticationEmailCode` | **present** |  |
| TG-AUTH-06 | Session teardown / logout | `Close, LogOut (implicit via Close)` | **present** | disconnect / wipeLocalData |
| TG-AUTH-07 | TDLib parameters + Tor SOCKS5 | `SetTdlibParameters, AddProxy(ProxyTypeSocks5), DisableProxy` | **present** | Tor mandatory; proxy before parameters |
| TG-DM-01 | Private DM create / open | `CreatePrivateChat, GetUser, SearchUserByPhoneNumber, SearchPublicChat` | **present** | startConversation paths |
| TG-DM-02 | Contacts list + refresh | `GetContacts, GetUser, ImportContacts` | **present** | refreshContacts / importContacts |
| TG-DM-03 | Block / unblock user | `SetMessageSenderBlockList(MessageSenderUser, BlockListMain|null)` | **missing** | AAR supports; not wrapped |
| TG-GROUP-01 | Basic group create | `CreateNewBasicGroupChat` | **present** | startConversation asGroup |
| TG-GROUP-02 | Supergroup / channel create | `CreateNewSupergroupChat` | **missing** | AAR supports |
| TG-GROUP-03 | Join by invite link | `JoinChatByInviteLink, CheckChatInviteLink, JoinChat` | **missing** |  |
| TG-GROUP-04 | Leave chat | `LeaveChat` | **missing** |  |
| TG-GROUP-05 | Add / ban members, admin rights | `AddChatMembers, BanChatMember, SetChatMemberStatus, GetChatAdministrators` | **missing** | Admin suite |
| TG-GROUP-06 | Chat title / description / photo | `SetChatTitle, SetChatDescription, SetChatPhoto` | **missing** |  |
| TG-CHANNEL-01 | Channel posts receive | `UpdateNewMessage + ChatTypeChannel / isChannelPost` | **partial** | Synced as chats; no channel-specific UX |
| TG-CHANNEL-02 | Channel discuss / comments | `GetMessageThreadHistory, MessageTopic` | **missing** |  |
| TG-SECRET-01 | Secret chats E2EE | `CreateNewSecretChat, *, useSecretChats` | **blocked** | Policy useSecretChats=false — fail-closed |
| TG-MSG-TEXT | Send / receive text | `SendMessage(InputMessageText), MessageText` | **present** |  |
| TG-MSG-PHOTO | Photo send/receive | `InputMessagePhoto, MessagePhoto, DownloadFile` | **present** |  |
| TG-MSG-VIDEO | Video send/receive | `InputMessageVideo, MessageVideo` | **present** |  |
| TG-MSG-DOC | Document / audio file | `InputMessageDocument, InputMessageAudio` | **present** |  |
| TG-MSG-VOICE | Voice notes | `InputMessageVoiceNote, MessageVoiceNote` | **present** |  |
| TG-MSG-VIDEONOTE | Video notes (round) | `InputMessageVideoNote, MessageVideoNote` | **partial** | Receive mapped; send missing |
| TG-MSG-GIF | GIF / animation | `InputMessageAnimation, MessageAnimation` | **present** |  |
| TG-MSG-STICKER | Stickers send + catalog | `InputMessageSticker, GetStickers, GetInstalledStickerSets` | **present** | listStickers |
| TG-MSG-LOCATION | Static + live location | `InputMessageLocation, InputMessageLiveLocation, EditMessageLiveLocation` | **partial** | Send/receive OK; live edit missing |
| TG-MSG-CONTACT | Contact card | `InputMessageContact, MessageContact` | **present** |  |
| TG-MSG-POLL | Polls send + vote | `InputMessagePoll, SetPollAnswer, MessagePoll` | **present** | votePoll |
| TG-MSG-POLL-STOP | Stop poll | `StopPoll` | **missing** |  |
| TG-MSG-DICE | Dice / animated emoji | `MessageDice, MessageAnimatedEmoji` | **partial** | Body fallback only |
| TG-MSG-GAME | Games | `MessageGame, SendMessage(InputMessageGame)` | **missing** | Low priority |
| TG-MSG-INVOICE | Invoices / payments content | `MessageInvoice, *Payment*` | **blocked** | Premium/payments fail-closed |
| TG-MSG-ALBUM | Media albums | `SendMessageAlbum, mediaAlbumId` | **missing** |  |
| TG-MSG-SYSTEM | Service messages | `MessageChat*, MessagePinMessage, MessageCall, …` | **partial** | Subset mapped to SYSTEM |
| TG-MSG-EDIT | Edit text | `EditMessageText` | **present** | editMessage |
| TG-MSG-EDIT-CAPTION | Edit caption / media | `EditMessageCaption, EditMessageMedia` | **missing** |  |
| TG-MSG-DELETE | Delete messages (revoke) | `DeleteMessages` | **present** |  |
| TG-MSG-FORWARD | Forward messages | `ForwardMessages` | **present** |  |
| TG-MSG-REPLY | Protocol reply (reply_to) | `SendMessage.replyTo=InputMessageReplyToMessage` | **partial** | Cap true but replyTo always null; UI only local quote |
| TG-MSG-SILENT | Silent send (no notification) | `MessageSendOptions.disableNotification` | **missing** |  |
| TG-MSG-SCHEDULE | Scheduled messages | `MessageSchedulingStateSendAtDate, GetChatScheduledMessages, EditMessageSchedulingState` | **missing** |  |
| TG-MSG-SPOILER | Spoiler text / media | `TextEntityTypeSpoiler, hasSpoiler on media` | **missing** |  |
| TG-MSG-TTL | Chat auto-delete TTL | `SetChatMessageAutoDeleteTime` | **partial** | API present; ephemeralMessages=false UI |
| TG-MSG-SELFDESTRUCT | Per-message self-destruct media | `MessageSelfDestructTypeTimer on photo/voice` | **partial** | sendSelfDestructMedia; not advertised |
| TG-REACT-ADD | Add emoji reaction | `AddMessageReaction(ReactionTypeEmoji)` | **present** |  |
| TG-REACT-REMOVE | Remove reaction | `RemoveMessageReaction` | **missing** |  |
| TG-REACT-SYNC | Inbound reaction updates | `UpdateMessageInteractionInfo, Message.interactionInfo` | **missing** | Local JSON only on add |
| TG-CHAT-LIST | Main chat list sync | `LoadChats, GetChats(ChatListMain), GetChat, updates` | **present** | syncChatList |
| TG-CHAT-ARCHIVE | Archive / unarchive | `AddChatToList(ChatListArchive|Main), UpdateChatPosition` | **present** |  |
| TG-CHAT-PIN | Pin chat in list | `ToggleChatIsPinned` | **present** |  |
| TG-CHAT-MUTE | Mute notifications | `SetChatNotificationSettings` | **present** |  |
| TG-CHAT-PIN-MSG | Pin / unpin message in chat | `PinChatMessage, UnpinChatMessage, UnpinAllChatMessages` | **missing** |  |
| TG-CHAT-DRAFT | Server drafts | `SetChatDraftMessage, UpdateChatDraftMessage` | **missing** |  |
| TG-CHAT-MARK-UNREAD | Mark unread | `ToggleChatIsMarkedAsUnread` | **missing** |  |
| TG-HIST-01 | History load / pagination | `GetChatHistory, OpenChat, CloseChat, ViewMessages` | **present** | loadMessageHistory |
| TG-HIST-02 | Mark read | `ViewMessages / OpenChat` | **present** | markRead |
| TG-TYPING | Typing indicators | `SendChatAction, UpdateChatAction` | **present** |  |
| TG-SEARCH-CHAT | Search in chat | `SearchChatMessages` | **missing** |  |
| TG-SEARCH-GLOBAL | Global message search | `SearchMessages` | **missing** |  |
| TG-SEARCH-CHATS | Search chats / public | `SearchChats, SearchPublicChat, SearchPublicChats` | **partial** | SearchPublicChat only |
| TG-FOLDER-01 | Chat folders list | `UpdateChatFolders, GetChatFolder, ChatListFolder` | **missing** |  |
| TG-FOLDER-02 | Create / edit / delete folder | `CreateChatFolder, EditChatFolder, DeleteChatFolder, ReorderChatFolders` | **missing** |  |
| TG-FOLDER-03 | Folder chat membership | `GetChats(ChatListFolder), AddChatToList` | **missing** |  |
| TG-TOPIC-01 | Forum topics list | `GetForumTopics, ForumTopic` | **missing** |  |
| TG-TOPIC-02 | Create / edit / delete topic | `CreateForumTopic, EditForumTopic, DeleteForumTopic` | **missing** |  |
| TG-TOPIC-03 | Send in topic | `SendMessage.topicId=MessageTopicForum` | **missing** | topicId always null |
| TG-BOT-01 | Start bot deep-link | `SendBotStartMessage` | **missing** |  |
| TG-BOT-02 | Inline bots / queries | `GetInlineQueryResults, SendInlineQueryResultMessage` | **missing** |  |
| TG-BOT-03 | Callback query buttons | `GetCallbackQueryAnswer, UpdateNewCallbackQuery, ReplyMarkup*` | **missing** | replyMarkup ignored |
| TG-BOT-04 | Web Apps / Mini Apps | `OpenWebApp, CloseWebApp, …` | **missing** |  |
| TG-PROFILE-01 | Get / update name + bio | `GetMe, GetUserFullInfo, SetName, SetBio` | **present** |  |
| TG-PROFILE-02 | Set profile photo | `SetProfilePhoto` | **present** |  |
| TG-PROFILE-03 | Username / privacy / emoji status | `SetUsername, SetUserPrivacySettingRules, SetEmojiStatus` | **missing** |  |
| TG-STICKER-CUSTOM | Custom emoji / favorite stickers | `GetCustomEmoji*, AddFavoriteSticker, GetFavoriteStickers` | **missing** |  |
| TG-BACKUP | Local JSON backup export | `exportBackup (app-level)` | **present** | Not TDLib cloud backup |
| TG-VOIP-01 | 1:1 VoIP calls | `CreateCall, AcceptCall, DiscardCall, SendCall* + tgcalls native` | **blocked** | No tgcalls natives in AAR/app |
| TG-VOIP-02 | Group calls / video chats | `CreateVideoChat, JoinGroupCall, …` | **blocked** | Requires VoIP stack |
| TG-STORIES-01 | Stories receive / send | `LoadActiveStories, SendStory, GetStory, …` | **missing** | capabilities.stories=false; implementable in TDLib but product flag off — implement API then gate |
| TG-PREMIUM-01 | Premium / Stars / payments | `Buy*, GetPaymentForm, Gift*, Boost*, …` | **blocked** | Premium payments fail-closed by policy |
| TG-PUSH | Push / device registration | `RegisterDevice, ProcessPushNotification` | **missing** | App is Tor/foreground-first; document |

## Coverage summary
- **present**: 32
- **partial**: 10
- **missing**: 35
- **blocked**: 5
- **Discrete feature IDs**: 82
- **Raw TdApi.Function count in AAR**: 1023 (many are Premium/AI/business/passport — folded into blocked/other)

## Implementation order (missing → present)
TG-AUTH-04, TG-DM-03, TG-GROUP-02, TG-GROUP-03, TG-GROUP-04, TG-GROUP-05, TG-GROUP-06, TG-CHANNEL-01, TG-CHANNEL-02, TG-MSG-VIDEONOTE, TG-MSG-LOCATION, TG-MSG-POLL-STOP, TG-MSG-DICE, TG-MSG-GAME, TG-MSG-ALBUM, TG-MSG-SYSTEM, TG-MSG-EDIT-CAPTION, TG-MSG-REPLY, TG-MSG-SILENT, TG-MSG-SCHEDULE, TG-MSG-SPOILER, TG-MSG-TTL, TG-MSG-SELFDESTRUCT, TG-REACT-REMOVE, TG-REACT-SYNC, TG-CHAT-PIN-MSG, TG-CHAT-DRAFT, TG-CHAT-MARK-UNREAD, TG-SEARCH-CHAT, TG-SEARCH-GLOBAL, TG-SEARCH-CHATS, TG-FOLDER-01, TG-FOLDER-02, TG-FOLDER-03, TG-TOPIC-01, TG-TOPIC-02, TG-TOPIC-03, TG-BOT-01, TG-BOT-02, TG-BOT-03, TG-BOT-04, TG-PROFILE-03, TG-STICKER-CUSTOM, TG-STORIES-01, TG-PUSH

## Blocked IDs
TG-SECRET-01, TG-MSG-INVOICE, TG-VOIP-01, TG-VOIP-02, TG-PREMIUM-01

