package com.opencloud.zuul.filter;

import com.opencloud.base.client.model.AccessAuthority;
import com.opencloud.base.client.model.GatewayIpLimitApisDto;
import com.opencloud.common.constants.CommonConstants;
import com.opencloud.common.constants.ResultEnum;
import com.opencloud.common.security.Authority;
import com.opencloud.common.utils.StringUtils;
import com.opencloud.zuul.configuration.ApiProperties;
import com.opencloud.zuul.locator.ApiResourceLocator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.ConfigAttribute;
import org.springframework.security.access.SecurityConfig;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;

import javax.servlet.http.HttpServletRequest;
import java.util.*;

/**
 * 自定义动态访问控制
 *
 * @author liuyadu
 */
@Slf4j
@Component
public class ApiAuthorizationManager {

    private ApiResourceLocator accessLocator;

    private ApiProperties apiProperties;

    private static final AntPathMatcher pathMatch = new AntPathMatcher();

    private Set<String> permitAll = new HashSet<>();

    private Set<String> authorityIgnores = new HashSet<>();


    public ApiAuthorizationManager(ApiResourceLocator accessLocator, ApiProperties apiProperties) {
        this.accessLocator = accessLocator;
        this.apiProperties = apiProperties;
        if (apiProperties != null) {
            if (apiProperties.getPermitAll() != null) {
                permitAll.addAll(apiProperties.getPermitAll());
            }
            if (apiProperties.getAuthorityIgnores() != null) {
                authorityIgnores.addAll(apiProperties.getAuthorityIgnores());
            }
        }
    }

    /**
     * 访问控制
     * 1.IP黑名单
     * 2.IP白名单
     * 3.权限控制
     *
     * @param request
     * @param authentication
     * @return
     */
    public boolean check(HttpServletRequest request, Authentication authentication) {
        if (!apiProperties.getAccessControl()) {
            return true;
        }
        String requestPath = getRequestPath(request);
        // 是否直接放行
        if (isPermitAll(requestPath)) {
            return true;
        }
        // 判断api是否需要认证
        boolean isAuth = isAuthAccess(requestPath);

        if (isAuth) {
            // 校验身份
            return checkAuthorities(request, authentication, requestPath);
        } else {
            return true;
        }
    }


    public boolean isPermitAll(String requestPath) {
        Iterator<String> it = permitAll.iterator();
        while (it.hasNext()) {
            String path = it.next();
            if (pathMatch.match(path, requestPath)) {
                return true;
            }
        }
        return false;
    }

    public boolean isNoAuthorityAllow(String requestPath) {
        Iterator<String> it = authorityIgnores.iterator();
        while (it.hasNext()) {
            String path = it.next();
            if (pathMatch.match(path, requestPath)) {
                return true;
            }
        }
        return false;
    }


    public boolean checkAuthorities(HttpServletRequest request, Authentication authentication, String requestPath) {
        Object principal = authentication.getPrincipal();
        // 已认证身份
        if (principal != null) {

            if (authentication instanceof AnonymousAuthenticationToken) {
                //check if this uri can be access by anonymous
                //return
            }
            if (isNoAuthorityAllow(requestPath)) {
                // 认证通过,并且无需权限
                return true;
            }
            return mathAuthorities(request, authentication, requestPath);
        }
        return false;
    }

    public boolean mathAuthorities(HttpServletRequest request, Authentication authentication, String requestPath) {
        Collection<ConfigAttribute> attributes = getAttributes(requestPath);
        if (authentication == null) {
            return false;
        } else {
            if (CommonConstants.ROOT.equals(authentication.getName())) {
                // 默认超级管理员账号,直接放行
                return true;
            }
            Collection<? extends GrantedAuthority> authorities = authentication.getAuthorities();
            Iterator var6 = attributes.iterator();
            while (var6.hasNext()) {
                ConfigAttribute attribute = (ConfigAttribute) var6.next();
                Iterator var8 = authorities.iterator();
                while (var8.hasNext()) {
                    GrantedAuthority authority = (GrantedAuthority) var8.next();
                    if (attribute.getAttribute().equals(authority.getAuthority())) {
                        if (authority instanceof Authority) {
                            Authority customer = (Authority) authority;
                            if (customer.getIsExpired() != null && customer.getIsExpired()) {
                                // 授权已过期
                                log.debug("==> access_denied:path={},message={}", requestPath, ResultEnum.ACCESS_DENIED_AUTHORITY_EXPIRED.getMessage());
                                request.setAttribute(CommonConstants.X_ACCESS_DENIED, ResultEnum.ACCESS_DENIED_AUTHORITY_EXPIRED);
                                return false;
                            }
                        }
                        return true;
                    }
                }
            }
            return false;
        }
    }

    public Collection<ConfigAttribute> getAttributes(String requestPath) {
        // 匹配动态权限
        for (Iterator<String> iter = accessLocator.getAllConfigAttribute().keySet().iterator(); iter.hasNext(); ) {
            String url = iter.next();
            // 防止匹配错误 忽略/**
            if (!"/**".equals(url) && pathMatch.match(url, requestPath)) {
                // 返回匹配到权限
                return accessLocator.getAllConfigAttribute().get(url);
            }
        }
        return SecurityConfig.createList("AUTHORITIES_REQUIRED");
    }


    public boolean matchIpBlacklist(String requestPath, String remoteIpAddress) {
        List<GatewayIpLimitApisDto> blackList = accessLocator.getIpBlackList();
        if (blackList != null) {
            for (GatewayIpLimitApisDto api : blackList) {
                if (pathMatch.match(api.getPath(), requestPath) && api.getIpAddressSet() != null && !api.getIpAddressSet().isEmpty()) {
                    if (matchIp(api.getIpAddressSet(), remoteIpAddress)) {
                        return true;
                    }
                }
            }
        }
        return false;

    }

    public boolean[] matchIpWhiteList(String requestPath, String remoteIpAddress) {
        boolean hasWhiteList = false;
        boolean allow = false;
        List<GatewayIpLimitApisDto> whiteList = accessLocator.getIpWhiteList();
        if (whiteList != null) {
            for (GatewayIpLimitApisDto api : whiteList) {
                if (pathMatch.match(api.getPath(), requestPath) && api.getIpAddressSet() != null && !api.getIpAddressSet().isEmpty()) {
                    hasWhiteList = true;
                    allow = matchIp(api.getIpAddressSet(), remoteIpAddress);
                    break;
                }
            }
        }
        return new boolean[]{hasWhiteList, allow};
    }

    public boolean matchIp(Set<String> ips, String remoteIpAddress) {
        IpAddressMatcher ipAddressMatcher = null;
        for (String ip : ips) {
            try {
                ipAddressMatcher = new IpAddressMatcher(ip);
                if (ipAddressMatcher.matches(remoteIpAddress)) {
                    return true;
                }
            } catch (Exception e) {
            }
        }
        return false;
    }

    public boolean isAuthAccess(String requestPath) {
        List<AccessAuthority> authorityList = accessLocator.getAuthorityList();
        if (authorityList != null) {
            for (AccessAuthority auth : authorityList) {
                String fullPath = auth.getPath();
                Boolean isAuth = auth.getIsAuth() != null && auth.getIsAuth().equals(1) ? true : false;
                // 无需认证,返回true
                if (StringUtils.isNotBlank(fullPath) && pathMatch.match(fullPath, requestPath) && !isAuth) {
                    return true;
                }
            }
        }
        return false;
    }

    public String getRequestPath(HttpServletRequest request) {
        String url = request.getServletPath();
        String pathInfo = request.getPathInfo();
        if (pathInfo != null) {
            url = StringUtils.isNotBlank(url) ? url + pathInfo : pathInfo;
        }
        return url;
    }
}