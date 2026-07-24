package com.viyangle.study_tour.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatGroupMember {
    private Long accountId;
    private String username;
    private String avatarUrl;
    /** PUBLISHER、LEADER 或 PARTICIPANT。 */
    private String memberRole;
    private Integer representedCount;

    public String getRepresentationText() {
        int count = representedCount == null ? 0 : representedCount;
        return "该用户代表" + count + "人";
    }
}
