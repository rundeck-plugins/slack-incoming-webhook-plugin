<#setting number_format="computer">
<#-- Slack treats &, < and > as markup (mentions, links), so escape them in job data -->
<#function slack text>
    <#return text?replace("&", "&amp;")?replace("<", "&lt;")?replace(">", "&gt;")>
</#function>
<#if executionData.job.group??>
    <#assign jobName="${slack(executionData.job.group)} / ${slack(executionData.job.name)}">
<#else>
    <#assign jobName=slack(executionData.job.name)>
</#if>
<#assign message="<${executionData.href}|Execution #${executionData.id}> of job <${executionData.job.href}|${jobName}>">
<#if trigger == "start">
    <#assign state="Started">
<#elseif trigger == "failure">
    <#assign state="Failed">
<#elseif trigger == "avgduration">
    <#assign state="Average exceeded">
<#elseif trigger == "retryablefailure">
   <#assign state="Retry Failure">
<#else>
   <#assign state="Succeeded">
</#if>

{
<#if channel??>
   "channel":"${channel?json_string}",
</#if>
   "attachments":[
      {
         "fallback":"${state?json_string}: ${message?json_string}",
         "pretext":"${message?json_string}",
         "color":"${color?json_string}",
         "fields":[
            {
               "title":"Job Name",
               "value":"<${executionData.job.href?json_string}|${jobName?json_string}>",
               "short":true
            },
            {
               "title":"Project",
               "value":"${slack(executionData.project)?json_string}",
               "short":true
            },
            {
               "title":"Status",
               "value":"${state?json_string}",
               "short":true
            },
            {
               "title":"Execution ID",
               "value":"<${executionData.href?json_string}|#${executionData.id}>",
               "short":true
            },
            {
               "title":"Options",
               "value":"${slack(executionData.argstring!"N/A")?json_string}",
               "short":true
            },
            {
               "title":"Started By",
               "value":"${slack(executionData.user)?json_string}",
               "short":true
            }
<#if trigger == "failure">
            ,{
               "title":"Failed Nodes",
               "value":"${slack(executionData.failedNodeListString!"- (Job itself failed)")?json_string}",
               "short":false
            }
</#if>
]
      }
   ]
}
