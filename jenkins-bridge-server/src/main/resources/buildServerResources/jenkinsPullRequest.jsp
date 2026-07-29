<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<c:set var="prLabel">#<c:out value="${prNumber}"/><c:if test="${not empty prTitle}">: <c:out value="${prTitle}"/></c:if></c:set>
<div class="jenkinsPullRequest">
  <div>
    <c:choose>
      <c:when test="${not empty prUrl}">
        <a href="<c:out value="${prUrl}"/>" target="_blank" rel="noopener noreferrer">${prLabel}</a>
      </c:when>
      <c:otherwise>${prLabel}</c:otherwise>
    </c:choose>
  </div>
  <div>
    <c:if test="${not empty prTargetBranch}">Submitted into <strong><c:out value="${prTargetBranch}"/></strong></c:if>
    <c:if test="${not empty prSourceBranch}">
      <c:if test="${not empty prTargetBranch}">,</c:if>
      from <strong><c:out value="${prSourceBranch}"/></strong>
    </c:if>
    <c:if test="${not empty prAuthor}">by <strong><c:out value="${prAuthor}"/></strong></c:if>
  </div>
</div>
