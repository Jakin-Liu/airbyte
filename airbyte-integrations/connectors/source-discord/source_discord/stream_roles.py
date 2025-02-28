#
# Copyright (c) 2022 Airbyte, Inc., all rights reserved.
#

from typing import Any, Iterable, Mapping, MutableMapping, Optional

import requests
from airbyte_cdk.sources.streams.http import HttpStream


class DiscordRolesStream(HttpStream):
    url_base = "https://discord.com"
    primary_key = "id"

    def __init__(self, config: Mapping[str, Any], **kwargs):
        super().__init__()
        self.guild_id = config["guild_id"]
        self.job_time = config["job_time"]
        self.server_token = config["server_token"]

    def request_headers(self, **_) -> Mapping[str, Any]:
        return {"Authorization": "Bot " + self.server_token}

    def request_params(self, next_page_token: Mapping[str, Any] = None, **_) -> MutableMapping[str, Any]:
        if not next_page_token:
            return None
        return next_page_token

    def parse_response(self, response: requests.Response, **_) -> Iterable[Mapping]:
        if response.status_code != 200:
            return []
        datas = response.json()
        result = []
        for i in datas:
            i["timestamp"] = self.job_time
            result.append(i)
        return result
        # yield from response.json()

    def next_page_token(self, response: requests.Response) -> Optional[Mapping[str, Any]]:
        return None


class Roles(DiscordRolesStream):
    def path(self, **_) -> str:
        return f"api/v10/guilds/{self.guild_id}/roles"
